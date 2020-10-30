package org.odk.collect.android.subform

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.odk.collect.android.database.DatabaseContext
import org.odk.collect.android.storage.StoragePathProvider
import org.odk.collect.android.storage.StorageSubdirectory
import timber.log.Timber

/*
 * Track information related to subforms.
 *
 * Constants, file names, SQL statements, and SQLiteOpenHelper are
 * provided in this file. A facade interface is provided as well
 * that lists out all necessary operations of form linking.
 *
 * In order to maintain a record of parent child form relations,
 * mapping is:
 *
 *          parent instance id + parent node
 *                           |
 *                           V
 *           child instance id + child node
 *
 * A node is where data is stored in the instance (data model) for a
 * given form instance.
 *
 * Furthermore, the `repeat index` and `repeatable` are tracked. The
 * repeat index is parsed out of the parent node. The repeatable is
 * the root of the repeat/group.
 */

val DATABASE_VERSION = 1
val DATABASE_NAME = "subforms.db"

val TABLE_NAME = "subforms"

val COLUMN_ID = "_ID"
val COLUMN_PARENT_INSTANCE_ID = "parent_id"
val COLUMN_REPEATABLE = "repeatable"
val COLUMN_PARENT_INDEX = "parent_index"
val COLUMN_CHILD_INSTANCE_ID = "child_id"
val COLUMN_PARENT_NODE = "parent_node"
val COLUMN_CHILD_NODE = "child_node"

val TEXT_TYPE = " TEXT"
val TEXT_TYPE_NOT_NULL = " TEXT NOT NULL"
val INT_TYPE = " INT"
val INT_TYPE_NOT_NULL = " INTEGER NOT NULL"
val COMMA_SEP = ", "

val CREATE_TABLE = "CREATE TABLE " +
        TABLE_NAME + "(" + COLUMN_ID + INT_TYPE_NOT_NULL + " PRIMARY KEY" + COMMA_SEP +
        COLUMN_PARENT_INSTANCE_ID + INT_TYPE_NOT_NULL + COMMA_SEP +
        COLUMN_PARENT_NODE + TEXT_TYPE_NOT_NULL + COMMA_SEP +
        COLUMN_REPEATABLE + TEXT_TYPE + COMMA_SEP +
        COLUMN_PARENT_INDEX + INT_TYPE + COMMA_SEP +
        COLUMN_CHILD_INSTANCE_ID + INT_TYPE_NOT_NULL + COMMA_SEP +
        COLUMN_CHILD_NODE + TEXT_TYPE_NOT_NULL + COMMA_SEP +
        "UNIQUE(" +
        COLUMN_PARENT_INSTANCE_ID + COMMA_SEP + COLUMN_PARENT_NODE +
        ")" +
        ");"

val DELETE_TABLE = "DROP TABLE IF EXISTS $TABLE_NAME"

class SubformDatabaseHelper : SQLiteOpenHelper(
        DatabaseContext(StoragePathProvider().getDirPath(StorageSubdirectory.METADATA)), DATABASE_NAME, null, DATABASE_VERSION)
{
    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL(CREATE_TABLE)
        Timber.i("Create subforms database")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db?.execSQL(DELETE_TABLE)
        db?.execSQL(CREATE_TABLE)
        Timber.i("Update subforms database")
    }
}

fun resetSubformDb() {
    Timber.i("Reset subforms database")
    with(SubformDatabaseHelper().writableDatabase ?: return) {
        execSQL(DELETE_TABLE)
        execSQL(CREATE_TABLE)
        close()
    }
}

/**
 * Inserts a row into the form relations database.
 *
 * For simplicity, all arguments should be strings (even though
 * `ContentValues.put()` can accept nearly all primitives). No checks
 * are performed for null values.
 *
 * @param parentId Parent instance id
 * @param parentNode Xpath for the node in the parent instance
 * @param repeatableNode Xpath for the root of the group/repeat
 * @param repeatIndex Repeat index in the parent instance associated with
 * the child instance. Null if and only if repeatableNode is null.
 * @param childId Child instance id
 * @param childNode Xpath for the node in the child instance
 * @param ignore Should a constraint violation be ignored?
 * @return Returns the id (primary key) of the newly inserted row. This is
 * not generally useful. If ignoring constraints and the row is not
 * inserted, return -1.
 */
fun insert(parentId: String, parentNode: String, repeatableNode: String?,
           repeatIndex: String, childId: String, childNode: String,
           ignore: Boolean = false): Long =
        with(SubformDatabaseHelper().writableDatabase) {
            try {
                insertOrThrow(TABLE_NAME, null, ContentValues().apply {
                    put(COLUMN_PARENT_INSTANCE_ID, parentId)
                    put(COLUMN_PARENT_NODE, parentNode)
                    put(COLUMN_PARENT_INDEX, repeatIndex)
                    put(COLUMN_CHILD_INSTANCE_ID, childId)
                    put(COLUMN_CHILD_NODE, childNode)
                    put(COLUMN_REPEATABLE, repeatableNode)
                })
            } catch (e: SQLException) {
                Timber.w("SQLException when inserting parentId $parentId, parentNode $parentNode, repeatIndex $repeatIndex, childId $childId, childNode $childNode")
                if (!ignore) throw e else -1
            } finally {
                close()
            }
        }

/**
 * Gets all relations
 */
fun getAllRelations(): Set<Pair<Long, Long>> =
    with(SubformDatabaseHelper().readableDatabase) {
        val projection = arrayOf(COLUMN_PARENT_INSTANCE_ID, COLUMN_CHILD_INSTANCE_ID)
        val cursor = query(TABLE_NAME, projection, null, null, null, null, null)
        val allRelations = mutableSetOf<Pair<Long, Long>>()
        cursor?.run {
            if (moveToFirst()) {
                while(moveToNext()) {
                    val thisParent = getLong(getColumnIndex(COLUMN_PARENT_INSTANCE_ID))
                    val thisChild = getLong(getColumnIndex(COLUMN_CHILD_INSTANCE_ID))
                    allRelations.add(thisParent to thisChild)
                }
            }
        }
        allRelations
    }
/**
 * Gets a child instance id based on the parent id and repeat index.
 *
 * Queries the database and looks only at the first record. There should
 * be only one child per parent instance id and repeat index.
 *
 * @param parentId The instance id of the parent form.
 * @param repeatIndex The repeat index in the parent form, greater than or
 * equal to 1.
 * @return Returns the instance id of the child form. Returns -1 if no
 * form is associated with the supplied parameters.
 */
fun getChild(parentId: Long, repeatIndex: Long): Long =
        with(SubformDatabaseHelper().readableDatabase) {

            val projection = arrayOf(COLUMN_CHILD_INSTANCE_ID)
            val selection = "$COLUMN_PARENT_INSTANCE_ID=? AND $COLUMN_PARENT_INDEX=?"
            val selectionArgs = arrayOf(parentId.toString(), repeatIndex.toString())

            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, null)
            val instanceId = cursor?.run{
                if (moveToFirst()) {
                    getLong(getColumnIndex(COLUMN_CHILD_INSTANCE_ID))
                } else {
                    -1
                }
            } ?: -1
            cursor?.close()
            close()
            Timber.d("getChild for parentId $parentId, and repeat $repeatIndex returning $instanceId from subform database")
            instanceId
        }

/**
 * Gets instance ids of all children associated with a parent form.
 *
 * Queries the database based on parentId. Uses a set to keep only the
 * unique values from the returned records. Initially, it was thought that
 * the children ids should be sorted. However, that seems not to be the
 * case.
 *
 * @param parentId The instance id of the parent form.
 * @return Returns instance ids of all children related to supplied
 * parent. Returns an array of primitive long rather than a set.
 * Empty if no children are discovered.
 */
fun getChildren(parentId: Long): List<Long> =
        with(SubformDatabaseHelper().readableDatabase) {
            val projection = arrayOf(COLUMN_CHILD_INSTANCE_ID)
            val selection = "$COLUMN_PARENT_INSTANCE_ID=?"
            val selectionArgs = arrayOf(parentId.toString())

            val uniqueChildren = mutableSetOf<Long>()
            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, null)
            cursor?.run{
                if (moveToFirst()) {
                    while (moveToNext()) {
                        val child = getLong(getColumnIndex(COLUMN_CHILD_INSTANCE_ID))
                        uniqueChildren.add(child)
                    }
                }
                close()
            }
            close()
            uniqueChildren.toList().sorted()
        }

/**
 * Gets the instance id of the parent form based on the child form.
 *
 * Queries the database and looks only at the first record. There should
 * be only one parent instance id per child instance id.
 *
 * @param childId The instance id of the child form.
 * @return Returns the instance id of the parent form. Returns -1 if no
 * form is associated with the `childId`.
 */
fun getParent(childId: Long): Long =
        with(SubformDatabaseHelper().readableDatabase) {
            val projection = arrayOf(COLUMN_PARENT_INSTANCE_ID)
            val selection = "COLUMN_CHILD_INSTANCE_ID=?"
            val selectionArgs = arrayOf(childId.toString())

            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, null)
            val parentId = cursor?.run {
                if (moveToFirst()) {
                    getLong(getColumnIndex(COLUMN_PARENT_INSTANCE_ID))
                } else {
                    -1
                }
            } ?: -1
            cursor?.close()
            close()
            parentId
        }

data class NodeMapping(val parentId: Long, val parentNode: String, val childId: Long, val childNode: String)

/**
 * Returns all paired instance nodes between parent and child forms.
 *
 * Queries the database based on child instance id and returns the nodes
 * that are paired together with its parent instance. These nodes should
 * have the same information, i.e. when one is updated during a survey,
 * the other is updated programmatically. A mapping is defined using the
 * `saveInstance=/XPath/to/node` attribute and value in an XForm.
 *
 * @param childId The instance id of the child form.
 * @return Returns a list of parent node / child node pairs. Returns empty
 * list if `childId` is not in the database.
 */
fun getMappingsToParent(childId: Long): List<NodeMapping> =
        with(SubformDatabaseHelper().readableDatabase) {
            val nodeMappings = mutableListOf<NodeMapping>()
            val projection = arrayOf(COLUMN_PARENT_INSTANCE_ID, COLUMN_PARENT_NODE, COLUMN_CHILD_NODE)
            val selection = "$COLUMN_CHILD_INSTANCE_ID=?"
            val selectionArgs = arrayOf(childId.toString())
            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, null)
            cursor?.run {
                if (moveToFirst()) {
                    while (moveToNext()) {
                        val parentId = getLong(getColumnIndex(COLUMN_PARENT_INSTANCE_ID))
                        val parentNode = getString(getColumnIndex(COLUMN_PARENT_NODE))
                        val childNode = getString(getColumnIndex(COLUMN_CHILD_NODE))
                        nodeMappings.add(NodeMapping(parentId, parentNode, childId, childNode))
                    }
                }
                close()
            }
            close()
            nodeMappings
        }

/**
 * Gets the instance ids of all children forms in the database.
 *
 * Scans the child instance id column of the form relations table and
 * builds a set of all unique ids. This is useful for displaying all
 * instances that are not children instances.
 *
 * @return Returns a set of all instance ids that are children forms.
 */
fun getAllChildren(): Set<Long> =
        with(SubformDatabaseHelper().readableDatabase) {
            val children = mutableSetOf<Long>()
            val projection = arrayOf(COLUMN_CHILD_INSTANCE_ID)
            val cursor = query(true, TABLE_NAME, projection, null, null, null, null, null, null)
            cursor?.run {
                if(moveToFirst()) {
                    while (moveToNext()) {
                        val thisChild = getLong(getColumnIndex(COLUMN_CHILD_INSTANCE_ID))
                        children.add(thisChild)
                    }
                }
                close()
            }
            close()
            children
        }

/**
 * Gets the repeat index based on parent and child ids.
 *
 * Queries the database and looks only at the first record. There should
 * be only one repeat index per parent instance id and child instance id.
 *
 * @param parentId The instance id of the parent form.
 * @param childId The instance id of the child form.
 * @return Returns the repeat index of the parent form. Returns null if
 * no repeat index is found.
 */
fun getRepeatIndex(parentId: Long, childId: Long): Long? =
        with(SubformDatabaseHelper().readableDatabase) {
            val projection = arrayOf(COLUMN_PARENT_INDEX)
            val selection = "$COLUMN_PARENT_INSTANCE_ID=? AND $COLUMN_CHILD_INSTANCE_ID=?"
            val selectionArgs = arrayOf(parentId.toString(), childId.toString())
            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, null)
            val repeatIndex = cursor?.run {
                val thisIndex = if (moveToFirst()) {
                    getLong(getColumnIndex(COLUMN_PARENT_INDEX))
                } else { null }
                close()
                thisIndex
            }
            close()
            repeatIndex
        }

/**
 * Gets the xpath for the repeatable where a child is created.
 *
 * Children forms are generally created inside a repeat construct within
 * the parent form. This method returns the xpath to the root of the
 * repeatable. This is useful for deleting the entire group/repeat that
 * created a child form.
 *
 * @param parentId The instance id of the parent form.
 * @param childId The instance id of the child form.
 * @return Returns the root node of the repeatable of the parent form.
 * Returns null if no repeatable is found or if the stored repeatables
 * are all null.
 */
fun getRepeatable(parentId: Long, childId: Long): String? =
        with(SubformDatabaseHelper().readableDatabase) {
            val projection = arrayOf(COLUMN_REPEATABLE)
            val selection = "$COLUMN_PARENT_INSTANCE_ID=? AND $COLUMN_CHILD_INSTANCE_ID=?"
            val selectionArgs = arrayOf(parentId.toString(), childId.toString())
            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, null)
            val repeatable = cursor?.run {
                val thisRepeatable = if (moveToFirst()) {
                    getString(getColumnIndex(COLUMN_PARENT_INDEX))
                } else { null }
                close()
                thisRepeatable
            }
            close()
            repeatable
        }

/**
 * Deletes records where supplied instance id is in parent id column.
 *
 * When deleting an instance, it is easiest to remove all references to
 * that instance by scanning parent id and child id columns. Thus, the
 * supplied id may not necessarily be assumed to be for a parent form.
 *
 * @param instanceId Instance id
 * @return Returns the number of rows deleted from the database.
 */
fun deleteAsParent(instanceId: Long): Long =
        with(SubformDatabaseHelper().writableDatabase) {
            val where = "$COLUMN_PARENT_INSTANCE_ID=?"
            val whereArgs = arrayOf(instanceId.toString())

            val recordsDeleted = delete(TABLE_NAME, where, whereArgs)
            close()
            recordsDeleted.toLong()
        }

/**
 * Deletes records where supplied instance id is in child id column.
 *
 * When deleting an instance, it is easiest to remove all references to
 * that instance by scanning parent id and child id columns. Thus, the
 * supplied id may not necessarily be assumed to be for a child form.
 *
 * Only call this delete method if the child information (repeatable) IS
 * NOT removed from the parent form. In PMA terms, this is akin to
 * changing the age to outside the relevant range (15-49).
 *
 * @param instanceId Instance id
 * @return Returns the number of rows deleted from the database.
 */
fun deleteAsChild(instanceId: Long): Long =
        with(SubformDatabaseHelper().writableDatabase) {
            val where = "$COLUMN_CHILD_INSTANCE_ID=?"
            val whereArgs = arrayOf(instanceId.toString())
            val recordsDeleted = delete(TABLE_NAME, where, whereArgs)
            close()
            recordsDeleted.toLong()
        }

/**
 * Updates repeat group sibling db info.
 *
 * Removing a repeat shifts the index of all subsequent nodes down by one.
 * After deleting the child instance from the form relations table,
 * sibling forms with a repeat index greater than what is supplied have
 * node and repeatable information updated.
 *
 * Only call this method if the child information (repeatable) IS removed
 * from the parent form. In PMA terms, this is akin to removing a repeat
 * node (household member information) during the household survey.
 *
 * @param parentId The instance id of the parent form.
 * @param repeatIndex The repeat index in the parent form, greater than or
 * equal to 1.
 * @return Returns the number of rows deleted from the database.
 */
fun updateRepeatSiblingInfo(parentId: Long, repeatIndex: Long) =
        with(SubformDatabaseHelper().writableDatabase) {
            Timber.d("Shifting down repeat indicies greater than $repeatIndex")
            // Now must shift indices down that are greater than repeatIndex
            // so that sibling information is correct.
            val projection = arrayOf(
                    COLUMN_ID,
                    COLUMN_PARENT_NODE,
                    COLUMN_PARENT_INDEX,
                    COLUMN_REPEATABLE
            )
            // According to https://www.sqlite.org/datatype3.html, section 3.3,
            // greater than (>) should be numeric, not string, comparison.
            val selection = "$COLUMN_PARENT_INSTANCE_ID=? AND $COLUMN_PARENT_INDEX>?"
            val selectionArgs = arrayOf(parentId.toString(), repeatIndex.toString())
            val cursor = query(TABLE_NAME, projection, selection, selectionArgs, null, null, COLUMN_PARENT_INDEX)
            cursor?.let {
                if (it.moveToFirst()) {
                    val recordId = it.getString(it.getColumnIndex(COLUMN_ID))
                    val foundRepeats = mutableListOf<Triple<String, Long, String>>()
                    while (it.moveToNext()) {
                        val oldParentNode = it.getString(it.getColumnIndex(COLUMN_PARENT_NODE))
                        val oldParentIndex = it.getLong(it.getColumnIndex(COLUMN_PARENT_INDEX))
                        val oldParentRepeatable = it.getString(it.getColumnIndex(COLUMN_REPEATABLE))
                        if (oldParentIndex.toInt() == 1) {
                            Timber.w("Trying to shift index down on " +
                                    "$oldParentNode. Index should be bigger than 1!")
                            continue
                        }
                        foundRepeats.add(Triple(oldParentNode, oldParentIndex, oldParentRepeatable))
                    }
                    foundRepeats.forEach { triple ->
                        val (oldParentNode, oldParentIndex, oldParentRepeatable) = triple
                        val newParentIndex = oldParentIndex - 1
                        val newParentNode = replaceIndex(oldParentNode, oldParentIndex,
                                newParentIndex)
                        val newParentRepeatable = replaceIndex(oldParentRepeatable, oldParentIndex,
                                newParentIndex)
                        val cv = ContentValues()
                        cv.put(COLUMN_PARENT_NODE, newParentNode)
                        cv.put(COLUMN_PARENT_INDEX, newParentIndex)
                        cv.put(COLUMN_REPEATABLE, newParentRepeatable)
                        Timber.d("Old node $oldParentNode -> new node $newParentNode")
                        Timber.d("Old index $oldParentIndex -> new index $newParentIndex")
                        Timber.d("Old repeatable $oldParentRepeatable -> new repeatable $newParentRepeatable")
                        val updateSelection = "$COLUMN_ID=?"
                        val updateSelectionArgs = arrayOf(recordId)
                        update(TABLE_NAME, cv, updateSelection, updateSelectionArgs)
                    }
                }
            }
            cursor.close()
            close()
        }

/**
 * Replaces an index, i.e. [#], with another one in an xpath
 *
 * This is a helper method for updating xpaths. It replaces all
 * occurrences of [oldIndex] with [newIndex] in the supplied string.
 *
 * @param node The xpath to the node.
 * @param oldIndex The old index.
 * @param newIndex The new index, usually one less than old index.
 * @return Returns the xpath to the node with the correct replacements.
 */
private fun replaceIndex(node: String, oldIndex: Long, newIndex: Long): String {
    val find = "[$oldIndex]"
    val replace = "[$newIndex]"
    val newNode = node.replace(find, replace)
    Timber.v("Replacing node index from \'$node\' to \'$newNode\'")
    return newNode
}