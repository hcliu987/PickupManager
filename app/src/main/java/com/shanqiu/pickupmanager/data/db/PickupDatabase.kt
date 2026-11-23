/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shanqiu.pickupmanager.data.model.DeletedPickupItem
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.SavedAddress

@Database(
    entities = [PickupItem::class, DeletedPickupItem::class, SavedAddress::class],
    version = 10,
    exportSchema = true
)
abstract class PickupDatabase : RoomDatabase() {
    
    abstract fun pickupItemDao(): PickupItemDao
    abstract fun deletedPickupItemDao(): DeletedPickupItemDao
    abstract fun savedAddressDao(): SavedAddressDao
    
    companion object {
        @Volatile
        private var INSTANCE: PickupDatabase? = null
        
        private const val DATABASE_NAME = "pickup_manager.db"
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deleted_pickup_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pickupCode TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        rawSms TEXT NOT NULL,
                        deletedTime INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_deleted_pickup_items_pickupCode_timestamp 
                    ON deleted_pickup_items(pickupCode, timestamp)
                """)
            }
        }
        
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pickup_items ADD COLUMN phoneNumber TEXT NOT NULL DEFAULT ''")
            }
        }
        
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_addresses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        addressTag TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_saved_addresses_addressTag 
                    ON saved_addresses(addressTag)
                """)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_saved_addresses_sortOrder 
                    ON saved_addresses(sortOrder)
                """)
            }
        }
        
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_addresses ADD COLUMN customDisplayName TEXT")
            }
        }
        
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_pickup_items_pickupCode")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pickup_items_pickupCode_receiveTime ON pickup_items(pickupCode, receiveTime)")
            }
        }
        
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pickup_items ADD COLUMN restoreTime INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 关键修复迁移：pickupCode 唯一索引
         *
         * 背景：原唯一索引是 (pickupCode, receiveTime) 组合，但 RuleMatchingScreen 等
         * 手动添加路径使用 System.currentTimeMillis() 作为 receiveTime，导致同一条短信
         * 在不同路径下产生 receiveTime 不同的两条重复记录，组合索引无法阻止。
         *
         * 修复：改为 pickupCode 单一列唯一索引，从数据库层强制去重。
         * 同时清理现有的重复数据（保留 receiveTime 最早的记录，删除其余）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 先清理现有重复数据：每个 pickupCode 保留 id 最小的记录
                //    （id 自增，最小 id 意味着最先入库，通常来自 SmsReceiver 的真实短信）
                db.execSQL("""
                    DELETE FROM pickup_items
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM pickup_items GROUP BY pickupCode
                    )
                """)
                // 2. 删除旧的组合唯一索引（如果存在）
                db.execSQL("DROP INDEX IF EXISTS index_pickup_items_pickupCode_receiveTime")
                // 3. 创建 pickupCode 单一列唯一索引（硬性去重）
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pickup_items_pickupCode ON pickup_items(pickupCode)")
            }
        }
        
        /**
         * 回补被「过期检查」误改写的历史记录。
         *
         * 旧版 ExpireCheckWorker 会把到期记录写成 status = 2（STATUS_EXPIRED），
         * 而列表查询只取 0（待取）和 1（已取），于是这些包裹从两个页签同时消失、
         * 再也找不回来 —— 即便用户其实还没去取。
         *
         * Worker 已改为不再改写状态（过期由展示层的 isExpired() 表达）。
         * 这里把历史上被改写过的记录恢复为待取，是否显示仍由各版本的显示天数决定。
         *
         * 本迁移不删除任何数据，只把 status 从 2 改回 0。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE pickup_items SET status = 0 WHERE status = 2")
            }
        }

        /**
         * R3 / H-03：把 `pickupCode` 的**唯一**索引降级为普通索引。
         *
         * 为什么必须动 schema，而不是只在应用层改判断：
         * 唯一索引会把"窗口外同码 = 新包裹"这条合法路径也一起挡死。
         * 应用层再怎么判断，`INSERT` 到了数据库一样被 IGNORE 掉，
         * 结果是**新包裹静默消失** —— 用户看不到，日志里也只有一行 debug。
         *
         * 迁移步骤（顺序不能换）：
         * 1. 归一化已有数据的 pickupCode，让历史记录与新的比较口径一致。
         *    （只做大小写/全角/空白/连字符收敛，不做任何猜测性改写。）
         * 2. 删除旧的唯一索引。
         * 3. 建同名的**非唯一**索引，供 `WHERE pickupCode = ?` 使用。
         *
         * **不删任何数据**。历史上因唯一索引冲突而"本该存在却没入库"的记录
         * 无法找回（它们从未被写入），这一点只能在 README 里如实说明。
         *
         * 归一化用纯 SQL 表达：UPPER() 处理字母，REPLACE() 处理空白与常见
         * 全角连字符。全角数字/字母的转换 SQLite 没有内置函数，且那类数据
         * 只会来自"通知通道"，历史库里极少；此处不为了完备性引入不可读的
         * 嵌套 REPLACE —— Kotlin 侧的 normalizePickupCode 会覆盖新写入的数据，
         * 旧数据的残余差异由 6 小时时间窗兜住（同码同窗内仍会被判为同一包裹）。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE pickup_items
                    SET pickupCode = REPLACE(
                            REPLACE(
                                REPLACE(
                                    REPLACE(UPPER(TRIM(pickupCode)), ' ', ''),
                                    char(12288), ''),
                                '－', '-'),
                            char(8212), '-')
                    WHERE pickupCode <> ''
                    """
                )
                db.execSQL("DROP INDEX IF EXISTS index_pickup_items_pickupCode")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pickup_items_pickupCode " +
                        "ON pickup_items(pickupCode)"
                )
            }
        }

        fun getDatabase(context: Context): PickupDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PickupDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    // 启用跨进程失效通知：小部件进程（:widgetProvider）写入后，
                    // 主进程的 Room 能自动通知 Flow 订阅者更新 UI
                    .enableMultiInstanceInvalidation()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
