package cn.ianzb.hyperrefine.hook.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumePolicyTest {
    private fun player(
        id: String,
        uid: Int = 10_276,
        pid: Int = 10_974,
        state: Int? = 2,
        usage: Int? = 1,
        stream: Int? = null,
    ) = AppVolumePolicy.Player(id, uid, pid, state, usage, stream)

    @Test fun appleMusicUsesRealPackageAndRetainsSeparateNativeVolumeKey() {
        val apps = AppVolumePolicy.targets(listOf(player("apple")),
            packagesForUid = { listOf("com.apple.android.music") },
            nameForUid = { "com.apple.android:10276" },
        )
        assertEquals(listOf(AppVolumeTarget(10_276, "com.apple.android.music", "com.apple.android:10276")), apps)
    }

    @Test fun ordinaryAppsKeepIndependentRowsAndOnlyRequestedAppChanges() {
        val packages = linkedMapOf(10_201 to "tv.danmaku.bili", 10_202 to "com.tencent.qqmusic",
            10_203 to "com.miui.player")
        val players = packages.map { (uid, pkg) -> player(pkg, uid = uid) }
        val apps = AppVolumePolicy.targets(players, packagesForUid = { listOf(packages.getValue(it)) })
        assertEquals(packages.values.toList(), apps.map { it.packageName })
        assertTrue(apps.all { it.volumeKey == it.packageName })
        apps.forEach { app ->
            val updates = mutableMapOf<String, Float>()
            assertEquals(1, AppVolumePolicy.applyVolume(players, app.uid, 0.3f, { id, v -> updates[id] = v }))
            assertEquals(mapOf(app.packageName to 0.3f), updates)
        }
    }

    @Test fun ordinaryUidDoesNotNeedProcessOrLauncherLookups() {
        val apps = AppVolumePolicy.targets(listOf(player("single")),
            packagesForUid = { listOf("single.app") },
            packagesForProcess = { _, _ -> error("single package does not need process enumeration") },
            hasLauncher = { error("single package does not need a launcher query") },
        )
        assertEquals(listOf(AppVolumeTarget(10_276, "single.app")), apps)
    }

    @Test fun allStartedPlayersOfUidChangeButPausedAndUnrelatedPlayersDoNot() {
        val players = listOf(player("old-paused", state = 3), player("track-1"), player("track-2"),
            player("stopped", state = 4), player("other-app", uid = 10_301),
            player("notification", usage = 5, stream = 5))
        val updates = mutableMapOf<String, Float>()
        assertEquals(2, AppVolumePolicy.applyVolume(players, 10_276, 0.15f, { id, v -> updates[id] = v }))
        assertEquals(mapOf("track-1" to 0.15f, "track-2" to 0.15f), updates)
        assertEquals(1, AppVolumePolicy.targets(players.filter { it.uid == 10_276 },
            packagesForUid = { listOf("com.apple.android.music") }).size)
    }

    @Test fun disappearingPlayerDoesNotPreventOtherActivePlayerFromUpdating() {
        val updates = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val count = AppVolumePolicy.applyVolume(listOf(player("released"), player("live")), 10_276, 0.2f,
            setVolume = { id, _ -> if (id == "released") error("player released") else updates.add(id) },
            onFailure = { failures.add(it) },
        )
        assertEquals(1, count)
        assertEquals(listOf("live"), updates)
        assertEquals(1, failures.size)
    }

    @Test fun officialMediaFiltersRetainLegacyMusicStreamAndExcludeOtherPlayback() {
        val players = listOf(player("media"), player("legacy", usage = 0, stream = 3),
            player("navigation-music", usage = 12, stream = 3),
            player("alarm", usage = 4, stream = 4), player("system", uid = 1000),
            player("idle", state = 1), player("unknown-state", state = null),
            player("unknown-attributes", usage = null))
        assertEquals(listOf("media", "legacy", "navigation-music"),
            AppVolumePolicy.activeMedia(players).map { it.configuration })
    }

    @Test fun sharedUidPrefersPlayingProcessOverAnotherLaunchablePackage() {
        val apps = AppVolumePolicy.targets(listOf(player("playing", pid = 77)),
            packagesForUid = { listOf("a.other", "z.playing") },
            packagesForProcess = { uid, pid ->
                assertEquals(10_276, uid)
                assertEquals(77, pid)
                listOf("z.playing", "unrelated.process")
            },
            hasLauncher = { it == "a.other" },
        )
        assertEquals("z.playing", apps.single().packageName)
    }

    @Test fun unavailableProcessMappingFallsBackToLaunchableThenStablePackage() {
        val players = listOf(player("playing"))
        val packages = { _: Int -> listOf("z.app", "a.service", "z.app") }
        assertEquals("z.app", AppVolumePolicy.targets(players, packages,
            hasLauncher = { it == "z.app" }).single().packageName)
        assertEquals("a.service", AppVolumePolicy.targets(players, packages).single().packageName)
        assertEquals("a.service", AppVolumePolicy.targets(players,
            packagesForUid = { listOf("a.service", "z.app") }).single().packageName)
    }

    @Test fun missingPackagesAndWallpaperDoNotProduceBogusRows() {
        assertTrue(AppVolumePolicy.targets(listOf(player("hidden")), { emptyList() }).isEmpty())
        assertTrue(AppVolumePolicy.targets(listOf(player("wallpaper")),
            { listOf("com.miui.miwallpaper") }).isEmpty())
        assertTrue(AppVolumePolicy.targets(listOf(player("not-a-package")),
            { listOf("com.apple.android:10276", "") }).isEmpty())
        assertEquals("real.app", AppVolumePolicy.targets(listOf(player("shared")),
            { listOf("com.miui.miwallpaper", "real.app") }).single().packageName)
    }

    @Test fun samePackageInDifferentUsersKeepsDistinctFullUids() {
        val players = listOf(player("primary"), player("cloned", uid = 99_910_276))
        val apps = AppVolumePolicy.targets(players, { listOf("com.apple.android.music") })
        assertEquals(listOf(10_276, 99_910_276), apps.map { it.uid })
        val updates = mutableListOf<String>()
        AppVolumePolicy.applyVolume(players, apps.last().uid, 0.4f, { id, _ -> updates.add(id) })
        assertEquals(listOf("cloned"), updates)
    }

    @Test fun invalidVolumeDoesNotWriteAndValidVolumeIsBounded() {
        val updates = mutableListOf<Float>()
        val players = listOf(player("live"))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            assertEquals(0, AppVolumePolicy.applyVolume(players, 10_276, value, { _, v -> updates.add(v) }))
        }
        assertTrue(updates.isEmpty())
        listOf(-1f, 0f, 1f, 2f).forEach { value ->
            assertEquals(1, AppVolumePolicy.applyVolume(players, 10_276, value, { _, v -> updates.add(v) }))
        }
        assertEquals(listOf(0f, 0f, 1f, 1f), updates)
    }

    @Test fun receiverValidatesPackageAndUidAndSupportsOldPackageOnlyBroadcast() {
        val packages = { uid: Int -> if (uid == 10_276) listOf("com.apple.android.music") else emptyList() }
        fun resolve(pkg: String, uid: Int) = AppVolumePolicy.resolveTarget(pkg, uid,
            uidForPackage = { if (it == "com.apple.android.music") 10_276 else null },
            packagesForUid = packages, nameForUid = { "com.apple.android:10276" },
        )
        val expected = AppVolumeTarget(10_276, "com.apple.android.music", "com.apple.android:10276")
        assertEquals(expected, resolve(expected.packageName, expected.uid))
        assertEquals(expected, resolve(expected.packageName, -1))
        assertNull(resolve(expected.packageName, 10_301))
        assertNull(resolve("com.apple.android:10276", 10_276))
        assertNull(resolve("missing.app", -1))
        assertNull(resolve(expected.packageName, 1000))
        assertNull(resolve(expected.packageName, -2))
    }

    @Test fun suppliedUserUidDoesNotFallBackToPrimaryUserUid() {
        val clonedUid = 99_910_276
        val target = AppVolumePolicy.resolveTarget("cloned.app", clonedUid,
            uidForPackage = { error("must not substitute a primary-user uid") },
            packagesForUid = { if (it == clonedUid) listOf("cloned.app") else emptyList() },
            nameForUid = { null },
        )
        assertEquals(AppVolumeTarget(clonedUid, "cloned.app"), target)
    }
}
