package com.hieu.p2p.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.crdt.CrdtOp
import com.hieu.p2p.mesh.crdt.Hlc
import com.hieu.p2p.mesh.crdt.OpLog
import com.hieu.p2p.mesh.dm.DirectMessage
import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.dm.DmStatus
import com.hieu.p2p.mesh.dm.MessageStore

/** SQLite persistence for the CRDT op log and direct messages. */
class MeshDatabase(context: Context) : SQLiteOpenHelper(context, "mesh.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE crdt_ops (
                origin INTEGER NOT NULL, seq INTEGER NOT NULL,
                collection TEXT NOT NULL, op_key TEXT NOT NULL, value TEXT,
                hlc_wall INTEGER NOT NULL, hlc_counter INTEGER NOT NULL,
                PRIMARY KEY (origin, seq))"""
        )
        db.execSQL(
            """CREATE TABLE direct_messages (
                id INTEGER PRIMARY KEY, peer INTEGER NOT NULL, outgoing INTEGER NOT NULL,
                body TEXT NOT NULL, ts INTEGER NOT NULL, status TEXT NOT NULL,
                attempts INTEGER NOT NULL, last_attempt INTEGER NOT NULL)"""
        )
        addImageColumns(db)
        addMediaColumns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addImageColumns(db)
        if (oldVersion < 3) addMediaColumns(db)
    }

    /** v3: voice notes and video clips. */
    private fun addMediaColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE direct_messages ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE direct_messages ADD COLUMN preview BLOB")
    }

    private fun addImageColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE direct_messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'")
        db.execSQL("ALTER TABLE direct_messages ADD COLUMN image_bytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE direct_messages ADD COLUMN chunks INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE direct_messages ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
    }

    val opLog = object : OpLog {
        override fun loadAll(): List<CrdtOp> = readableDatabase.query(
            "crdt_ops", null, null, null, null, null, "origin, seq"
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val origin = NodeId(c.getLong(0))
                    add(
                        CrdtOp(
                            origin, c.getLong(1), c.getString(2), c.getString(3),
                            if (c.isNull(4)) null else c.getString(4),
                            Hlc(c.getLong(5), c.getInt(6), origin),
                        )
                    )
                }
            }
        }

        override fun append(ops: List<CrdtOp>) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (op in ops) {
                    val values = ContentValues().apply {
                        put("origin", op.origin.raw)
                        put("seq", op.seq)
                        put("collection", op.collection)
                        put("op_key", op.key)
                        put("value", op.value)
                        put("hlc_wall", op.hlc.wall)
                        put("hlc_counter", op.hlc.counter)
                    }
                    db.insertWithOnConflict("crdt_ops", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    val messages = object : MessageStore {
        override fun loadAll(): List<DirectMessage> = readableDatabase.query(
            "direct_messages",
            arrayOf(
                "id", "peer", "outgoing", "body", "ts", "status", "attempts", "last_attempt",
                "kind", "image_bytes", "chunks", "progress", "duration_ms", "preview",
            ),
            null, null, null, null, "ts",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DirectMessage(
                            c.getLong(0), NodeId(c.getLong(1)), c.getInt(2) == 1, c.getString(3),
                            c.getLong(4), DmStatus.valueOf(c.getString(5)), c.getInt(6), c.getLong(7),
                            DmKind.valueOf(c.getString(8)), c.getInt(9), c.getInt(10), c.getInt(11),
                            c.getInt(12), if (c.isNull(13)) null else c.getBlob(13),
                        )
                    )
                }
            }
        }

        override fun upsert(message: DirectMessage) {
            val values = ContentValues().apply {
                put("id", message.id)
                put("peer", message.peer.raw)
                put("outgoing", if (message.outgoing) 1 else 0)
                put("body", message.text)
                put("ts", message.timestamp)
                put("status", message.status.name)
                put("attempts", message.attempts)
                put("last_attempt", message.lastAttempt)
                put("kind", message.kind.name)
                put("image_bytes", message.imageBytes)
                put("chunks", message.chunks)
                put("progress", message.progress)
                put("duration_ms", message.durationMs)
                put("preview", message.preview)
            }
            writableDatabase.insertWithOnConflict("direct_messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}
