package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration

/**
 * Every schema migration of [app.knotwork.android.data.local.AppDatabase], oldest first.
 *
 * The one list the database builder in [app.knotwork.android.di.AppModule] registers, so a
 * migration exists for the app only once it is here. Each step lives in its own
 * `Migration<old>To<new>.kt` file; a schema bump adds that file and appends its constant
 * below. `AppDatabaseMigrationChainTest` fails when the list has a gap or does not end at
 * the version Room builds, and the instrumented `AppDatabaseMigrationHelperTest` runs the
 * whole list across the exported-schema range.
 */
val ALL_MIGRATIONS: List<Migration> = listOf(
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
    MIGRATION_20_21,
    MIGRATION_21_22,
    MIGRATION_22_23,
    MIGRATION_23_24,
    MIGRATION_24_25,
    MIGRATION_25_26,
    MIGRATION_26_27,
    MIGRATION_27_28,
    MIGRATION_28_29,
    MIGRATION_29_30,
    MIGRATION_30_31,
    MIGRATION_31_32,
    MIGRATION_32_33,
    MIGRATION_33_34,
    MIGRATION_34_35,
    MIGRATION_35_36,
    MIGRATION_36_37,
    MIGRATION_37_38,
    MIGRATION_38_39,
    MIGRATION_39_40,
    MIGRATION_40_41,
    MIGRATION_41_42,
    MIGRATION_42_43,
    MIGRATION_43_44,
    MIGRATION_44_45,
    MIGRATION_45_46,
    MIGRATION_46_47,
    MIGRATION_47_48,
    MIGRATION_48_49,
    MIGRATION_49_50,
    MIGRATION_50_51,
    MIGRATION_51_52,
    MIGRATION_52_53,
    MIGRATION_53_54,
    MIGRATION_54_55,
    MIGRATION_55_56,
    MIGRATION_56_57,
    MIGRATION_57_58,
    MIGRATION_58_59,
    MIGRATION_59_60,
    MIGRATION_60_61,
    MIGRATION_61_62,
    MIGRATION_62_63,
    MIGRATION_63_64,
    MIGRATION_64_65,
    MIGRATION_65_66,
    MIGRATION_66_67,
    MIGRATION_67_68,
    MIGRATION_68_69,
    MIGRATION_69_70,
)
