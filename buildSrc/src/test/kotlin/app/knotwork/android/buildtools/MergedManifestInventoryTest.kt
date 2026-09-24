package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [MergedManifestInventory].
 *
 * The inventory is only worth committing if it names everything a library can
 * add without a line of this repository changing — a requested permission, a
 * declared one, an exported component, a `queries` entry — and names nothing
 * that is not an entry surface, since an expectation that churns on every
 * dependency bump gets approved without being read.
 */
class MergedManifestInventoryTest {

    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.example">
            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
            <uses-permission-sdk-23 android:name="android.permission.ACCESS_WIFI_STATE" />
            <permission android:name="app.example.INTERNAL" android:protectionLevel="signature" />
            <queries>
                <package android:name="com.example.companion" />
                <intent>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:scheme="https" />
                </intent>
                <provider android:authorities="com.example.provider" />
            </queries>
            <application>
                <activity android:name="app.example.MainActivity" android:exported="true" />
                <activity android:name="app.example.SettingsActivity" android:exported="false" />
                <service
                    android:name="androidx.work.impl.background.systemjob.SystemJobService"
                    android:exported="true"
                    android:permission="android.permission.BIND_JOB_SERVICE" />
                <receiver android:name="app.example.InternalReceiver" />
                <provider android:name="androidx.startup.InitializationProvider" android:exported="false" />
                <activity-alias android:name="app.example.Alias" android:exported="true" />
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `given a manifest then every entry surface is listed once in a stable order`() {
        assertEquals(
            listOf(
                "exported activity app.example.MainActivity permission=none",
                "exported activity-alias app.example.Alias permission=none",
                "exported service androidx.work.impl.background.systemjob.SystemJobService " +
                    "permission=android.permission.BIND_JOB_SERVICE",
                "permission app.example.INTERNAL protectionLevel=signature",
                "queries intent action=android.intent.action.VIEW data=scheme:https",
                "queries package com.example.companion",
                "queries provider com.example.provider",
                "uses-permission android.permission.INTERNET",
                "uses-permission android.permission.READ_EXTERNAL_STORAGE maxSdkVersion=32",
                "uses-permission-sdk-23 android.permission.ACCESS_WIFI_STATE",
            ),
            MergedManifestInventory.of(manifest),
        )
    }

    @Test
    fun `given a component that is not exported then it is not an entry surface`() {
        val lines = MergedManifestInventory.of(manifest)

        assertTrue(lines.none { "SettingsActivity" in it || "InternalReceiver" in it || "InitializationProvider" in it })
    }

    @Test
    fun `given an export decided by a resource then it is listed rather than assumed closed`() {
        // `android:exported="@bool/…"` is resolved at runtime, per configuration.
        // Reading only the literal "true" would drop the component from the census
        // and let it change unnoticed, so anything that is not literally "false"
        // counts as exported.
        val lines = MergedManifestInventory.of(
            manifest.replace(
                "<receiver android:name=\"app.example.InternalReceiver\" />",
                "<receiver android:name=\"lib.FlagReceiver\" android:exported=\"@bool/lib_exported\" />",
            ),
        )

        assertTrue(lines.contains("exported receiver lib.FlagReceiver permission=none"))
    }

    @Test
    fun `given an exported provider then its read and write permissions are part of its line`() {
        // A provider can be guarded by readPermission / writePermission alone;
        // dropping one of them opens it while `android:permission` stays absent.
        val lines = MergedManifestInventory.of(
            manifest.replace(
                "</application>",
                "<provider android:name=\"lib.Files\" android:authorities=\"lib.files\" android:exported=\"true\" " +
                    "android:readPermission=\"lib.READ\" android:writePermission=\"lib.WRITE\" /></application>",
            ),
        )

        assertTrue(
            lines.contains("exported provider lib.Files permission=none readPermission=lib.READ writePermission=lib.WRITE"),
        )
    }

    @Test(expected = Exception::class)
    fun `given a manifest with a document type declaration then it is refused`() {
        // A merged manifest never carries one; refusing it keeps the parser from
        // resolving external entities for whatever file ends up as the input.
        MergedManifestInventory.of(
            manifest.replace(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE manifest [<!ENTITY x \"y\">]>",
            ),
        )
    }

    @Test
    fun `given an expectation file then comments and blank lines are not entries`() {
        val expectation = """
            # Requested by WorkManager.
            uses-permission android.permission.RECEIVE_BOOT_COMPLETED

              exported activity app.example.MainActivity permission=none   # the launcher
        """.trimIndent()

        assertEquals(
            listOf(
                "uses-permission android.permission.RECEIVE_BOOT_COMPLETED",
                "exported activity app.example.MainActivity permission=none",
            ),
            MergedManifestInventory.parseExpectation(expectation),
        )
    }

    @Test
    fun `given a library that adds a permission and an export then the drift names both as added`() {
        val before = MergedManifestInventory.of(manifest)
        val after = MergedManifestInventory.of(
            manifest
                .replace(
                    "<application>",
                    "<uses-permission android:name=\"android.permission.RECEIVE_BOOT_COMPLETED\" />\n<application>",
                )
                .replace(
                    "</application>",
                    "<receiver android:name=\"lib.BootReceiver\" android:exported=\"true\" /></application>",
                ),
        )

        val drift = MergedManifestInventory.compare(expected = before, actual = after)

        assertEquals(
            listOf(
                "exported receiver lib.BootReceiver permission=none",
                "uses-permission android.permission.RECEIVE_BOOT_COMPLETED",
            ),
            drift.added,
        )
        assertEquals(emptyList<String>(), drift.removed)
    }

    @Test
    fun `given an export that lost its permission then the drift shows the old line removed and the new one added`() {
        // The case a name-only inventory would wave through: same component,
        // now reachable by every installed app.
        val loosened = manifest.replace(
            "android:permission=\"android.permission.BIND_JOB_SERVICE\"",
            "",
        )

        val drift = MergedManifestInventory.compare(
            expected = MergedManifestInventory.of(manifest),
            actual = MergedManifestInventory.of(loosened),
        )

        assertEquals(
            listOf("exported service androidx.work.impl.background.systemjob.SystemJobService permission=none"),
            drift.added,
        )
        assertEquals(1, drift.removed.size)
        assertTrue(drift.removed.single().endsWith("permission=android.permission.BIND_JOB_SERVICE"))
    }

    @Test
    fun `given identical inventories then there is no drift`() {
        val lines = MergedManifestInventory.of(manifest)

        assertTrue(MergedManifestInventory.compare(lines, lines).isEmpty)
    }

    @Test
    fun `given the release manifests either side of the commit that added WorkManager then the drift names what the library brought`() {
        // A genuine divergence, not a mutation: the merged release manifests of
        // `091f8e27^` and `091f8e27`, the commit that added `androidx.work`. The
        // app's own manifest did not change in that commit; two permissions — run
        // at boot, hold a wake lock — and two exported components arrived with the
        // library, and nothing in the repository recorded that they had. The
        // fixtures are those build outputs with one edit: the app's pre-rename
        // package and theme names replaced by the current ones.
        fun inventory(name: String) = MergedManifestInventory.of(
            checkNotNull(javaClass.getResource("/merged-manifest/$name")) { "missing fixture $name" }.readText(),
        )
        val before = inventory("release-before-workmanager.xml")
        val after = inventory("release-with-workmanager.xml")

        val drift = MergedManifestInventory.compare(expected = before, actual = after)

        assertEquals(
            listOf(
                "exported receiver androidx.work.impl.diagnostics.DiagnosticsReceiver permission=android.permission.DUMP",
                "exported service androidx.work.impl.background.systemjob.SystemJobService " +
                    "permission=android.permission.BIND_JOB_SERVICE",
                "uses-permission android.permission.RECEIVE_BOOT_COMPLETED",
                "uses-permission android.permission.WAKE_LOCK",
            ),
            drift.added,
        )
        assertEquals(emptyList<String>(), drift.removed)
    }

    @Test
    fun `given the same entry listed twice in the expectation then it is reported`() {
        // A duplicate would hide the removal of one of its copies.
        val lines = MergedManifestInventory.of(manifest)

        val drift = MergedManifestInventory.compare(expected = lines + lines.first(), actual = lines)

        assertEquals(listOf(lines.first()), drift.duplicated)
        assertTrue(!drift.isEmpty)
    }

    @Test
    fun `given a release manifest that still declares the data-transport components then each is an occurrence`() {
        // The state the foss overlay removes: three components, none of them
        // exported, so the entry inventory lists none of them.
        val manifest = checkNotNull(javaClass.getResource("/merged-manifest/release-with-workmanager.xml")).readText()

        val found = MergedManifestInventory.occurrences(manifest, listOf("com.google.android.datatransport"))

        assertEquals(
            listOf(
                "com.google.android.datatransport in service " +
                    "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                "com.google.android.datatransport in meta-data " +
                    "backend:com.google.android.datatransport.cct.CctBackendFactory",
                "com.google.android.datatransport in service " +
                    "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
                "com.google.android.datatransport in receiver " +
                    "com.google.android.datatransport.runtime.scheduling.jobscheduling." +
                    "AlarmManagerSchedulerBroadcastReceiver",
            ),
            found,
        )
        assertTrue(MergedManifestInventory.of(manifest).none { it.contains("datatransport") })
    }

    @Test
    fun `given a forbidden text in no attribute then there is no occurrence`() {
        assertEquals(emptyList<String>(), MergedManifestInventory.occurrences(manifest, listOf("com.google.firebase")))
    }

    @Test
    fun `given a forbidden text in a queries entry then it is an occurrence too`() {
        val found = MergedManifestInventory.occurrences(manifest, listOf("com.example.companion"))

        assertEquals(listOf("com.example.companion in package com.example.companion"), found)
    }

    @Test
    fun `given an expectation with absent lines then they are absences and not entries`() {
        val text = """
            # header
            uses-permission android.permission.INTERNET
            absent com.google.firebase   # no Firebase component in this flavour
            absent com.google.android.gms
        """.trimIndent()

        assertEquals(
            listOf("uses-permission android.permission.INTERNET"),
            MergedManifestInventory.parseExpectation(text),
        )
        assertEquals(
            listOf("com.google.firebase", "com.google.android.gms"),
            MergedManifestInventory.parseAbsences(text),
        )
    }
}
