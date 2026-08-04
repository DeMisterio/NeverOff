package com.neveroff.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.neveroff.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SupportFactory

@Dao
interface ChatDao {
    @Query("SELECT * FROM local_chats ORDER BY updatedAt DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Query("DELETE FROM local_chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String)

    @Query("SELECT * FROM local_chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Query("SELECT * FROM local_chats WHERE id = :chatId LIMIT 1")
    fun observeChatById(chatId: String): Flow<ChatEntity?>

    @Query("UPDATE local_chats SET lastMessage = :lastMessage, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun updateLastMessage(chatId: String, lastMessage: String, updatedAt: Long)

    @Query("UPDATE local_chats SET lastMessage = :lastMessage, updatedAt = :updatedAt, unreadCount = unreadCount + 1 WHERE id = :chatId")
    suspend fun updateLastMessageAndIncrementUnread(chatId: String, lastMessage: String, updatedAt: Long)

    @Query("UPDATE local_chats SET unreadCount = 0 WHERE id = :chatId")
    suspend fun resetUnreadCount(chatId: String)

    @Query("UPDATE local_chats SET wallpaperUri = :wallpaperUri, myBubbleColor = :myBubbleColor, otherBubbleColor = :otherBubbleColor WHERE id = :chatId")
    suspend fun updateChatCustomization(chatId: String, wallpaperUri: String?, myBubbleColor: Long?, otherBubbleColor: Long?): Int

    @Query("UPDATE local_chats SET customName = :customName WHERE id = :chatId")
    suspend fun updateChatCustomName(chatId: String, customName: String?)

    @Query("UPDATE local_chats SET isLocked = :isLocked, pinHash = :pinHash, pinSalt = :pinSalt WHERE id = :chatId")
    suspend fun updateChatLockState(chatId: String, isLocked: Boolean, pinHash: String?, pinSalt: String?)
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT 20")
    fun getRecentSearches(): Flow<List<RecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentSearch(recentSearch: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE userId = :userId")
    suspend fun deleteRecentSearch(userId: String)
}
@Dao
interface MessageDao {
    @Query("SELECT * FROM local_messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    /**
     * Corrects the timestamp on a row that already exists.
     *
     * insertMessages IGNOREs conflicts, which is right — a blanket REPLACE
     * would overwrite `content`, and if a decrypt failed on this pass that
     * would swap good plaintext for "[Encrypted message]" permanently.
     *
     * But IGNORE also means a row cached with a wrong timestamp is never
     * corrected by any amount of re-syncing. Every message fetched before the
     * timestamp parser was fixed was stamped with the moment it was
     * downloaded, so this repairs them in place, touching only the column that
     * was wrong.
     */
    @Query("UPDATE local_messages SET createdAt = :createdAt WHERE id = :id AND createdAt <> :createdAt")
    suspend fun repairTimestamp(id: String, createdAt: Long)

    @Query("UPDATE local_messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: Int)

    @Query("SELECT * FROM local_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("UPDATE local_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("UPDATE local_messages SET status = 2 WHERE id IN (:ids)")
    suspend fun markMessagesAsRead(ids: List<String>)

    @Query("SELECT * FROM local_messages WHERE chatId = :chatId AND senderId != :myId AND status < 2")
    suspend fun getUnreadMessagesForChat(chatId: String, myId: String): List<MessageEntity>

    @Query("UPDATE local_messages SET status = 2 WHERE chatId = :chatId AND senderId != :myId AND status < 2")
    suspend fun markAllMessagesInChatAsRead(chatId: String, myId: String)

    @Query("SELECT COUNT(*) FROM local_messages WHERE chatId = :chatId AND status < 2 AND senderId != :myId")
    fun getUnreadCount(chatId: String, myId: String): Flow<Int>

    @Query("SELECT MAX(createdAt) FROM local_messages")
    suspend fun getLatestMessageTimestamp(): Long?

    @Query("DELETE FROM local_messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<String>)

    @Query("DELETE FROM local_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM local_messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No schema changes needed between v2 and v3 — just prevents destructive wipe
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_chats ADD COLUMN wallpaperUri TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE local_chats ADD COLUMN myBubbleColor INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE local_chats ADD COLUMN otherBubbleColor INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE local_profiles ADD COLUMN avatarUrl TEXT DEFAULT NULL")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`userId` TEXT NOT NULL, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `avatarUrl` TEXT, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`userId`))")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_messages_chatId` ON `local_messages` (`chatId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_messages_chatId_createdAt` ON `local_messages` (`chatId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_messages_createdAt` ON `local_messages` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_chats_updatedAt` ON `local_chats` (`updatedAt`)")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_chats ADD COLUMN customName TEXT DEFAULT NULL")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_chats ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE local_chats ADD COLUMN pinHash TEXT DEFAULT NULL")
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_chats ADD COLUMN pinSalt TEXT DEFAULT NULL")
    }
}

/** Channels: local mirror of broadcast feeds and their posts. */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_channels` (
                `id` TEXT NOT NULL,
                `ownerId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT,
                `avatarUrl` TEXT,
                `username` TEXT NOT NULL,
                `isPublic` INTEGER NOT NULL DEFAULT 1,
                `allowedReactions` TEXT NOT NULL DEFAULT '',
                `subscriberCount` INTEGER NOT NULL DEFAULT 0,
                `isSubscribed` INTEGER NOT NULL DEFAULT 0,
                `notificationsEnabled` INTEGER NOT NULL DEFAULT 1,
                `lastPost` TEXT,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                `unreadCount` INTEGER NOT NULL DEFAULT 0,
                `lastReadAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_channels_updatedAt` ON `local_channels` (`updatedAt`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_channels_username` ON `local_channels` (`username`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_channel_posts` (
                `id` TEXT NOT NULL,
                `channelId` TEXT NOT NULL,
                `authorId` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `viewCount` INTEGER NOT NULL DEFAULT 0,
                `reactionCounts` TEXT NOT NULL DEFAULT '',
                `myReaction` TEXT,
                `viewLogged` INTEGER NOT NULL DEFAULT 0,
                `sendState` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_channel_posts_channelId` ON `local_channel_posts` (`channelId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_channel_posts_channelId_createdAt` ON `local_channel_posts` (`channelId`, `createdAt`)")
    }
}

@Database(
    entities = [
        MessageEntity::class,
        ChatEntity::class,
        ProfileEntity::class,
        RecentSearchEntity::class,
        ChannelEntity::class,
        ChannelPostEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun channelDao(): ChannelDao
    abstract fun channelPostDao(): ChannelPostDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbKey = CryptoManager.getOrGenerateDatabaseKey()
                val factory = SupportFactory(dbKey.toByteArray())
                
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "neveroff_db"
                )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration() // last-resort safety net for dev builds
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Closes and deletes the local database on logout, so the next getDatabase()
         * call rebuilds it fresh under a new (post-reset) SQLCipher key.
         */
        fun resetInstance(context: Context) {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                INSTANCE = null
                context.applicationContext.deleteDatabase("neveroff_db")
            }
        }
    }
}
