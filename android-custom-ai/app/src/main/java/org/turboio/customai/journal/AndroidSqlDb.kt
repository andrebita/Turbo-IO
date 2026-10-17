package org.turboio.customai.journal

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** [SqlDb] over the app-private Android SQLite database (encrypted at rest by Android file-based encryption). */
class AndroidSqlDb(context: Context, name: String = "turboio_journal.db") : SqlDb {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {}
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun bind(args: Array<out Any?>): Array<Any?> = args.map { if (it is Boolean) (if (it) 1 else 0) else it }.toTypedArray()

    @Synchronized override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bind(args))
    }

    @Synchronized override fun insert(sql: String, vararg args: Any?): Long {
        db.compileStatement(sql).use { statement ->
            bind(args).forEachIndexed { i, v ->
                when (v) {
                    null -> statement.bindNull(i + 1)
                    is Long -> statement.bindLong(i + 1, v)
                    is Int -> statement.bindLong(i + 1, v.toLong())
                    is Double -> statement.bindDouble(i + 1, v)
                    is ByteArray -> statement.bindBlob(i + 1, v)
                    else -> statement.bindString(i + 1, v.toString())
                }
            }
            return statement.executeInsert()
        }
    }

    @Synchronized override fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val rows = ArrayList<Map<String, Any?>>()
            while (cursor.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (i in 0 until cursor.columnCount) {
                    row[cursor.getColumnName(i)] = when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                        else -> null
                    }
                }
                rows.add(row)
            }
            rows
        }

    @Synchronized override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try { val result = block(); d.setTransactionSuccessful(); return result } finally { d.endTransaction() }
    }
}
