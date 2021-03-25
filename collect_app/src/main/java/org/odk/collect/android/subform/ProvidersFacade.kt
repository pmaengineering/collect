package org.odk.collect.android.subform

import android.content.ContentValues
import android.net.Uri
import org.odk.collect.android.application.Collect
import org.odk.collect.android.dao.FormsDao
import org.odk.collect.android.dao.InstancesDao
import org.odk.collect.android.dao.helpers.ContentResolverHelper
import org.odk.collect.android.instances.Instance
import org.odk.collect.android.provider.FormsProviderAPI
import org.odk.collect.android.provider.InstanceProviderAPI
import java.io.File

/**
 * Adds to InstanceProvider using information from the form definition.
 *
 * After an instance is created and written to disk, it must be added to
 * the InstanceProvider. That happens here. This is mostly copypasta from
 * the private method inside the SaveToDiskTask#updateInstanceDatabase.
 *
 * @param formUri The Uri for the form template for the instance.
 * @param instancePath The path to disk where the instance has been saved.
 * @return Returns the Uri of the record that was created in the
 * InstanceProvider.
 */
fun updateInstanceDatabase(formUri: Uri, instancePath: String, displayName: String): Uri {
    val values = ContentValues()
    values.put(InstanceProviderAPI.InstanceColumns.STATUS, Instance.STATUS_INCOMPLETE)
    values.put(InstanceProviderAPI.InstanceColumns.CAN_EDIT_WHEN_COMPLETE, true.toString())
    values.put(InstanceProviderAPI.InstanceColumns.INSTANCE_FILE_PATH, instancePath)
    if (displayName != "") {
        values.put(InstanceProviderAPI.InstanceColumns.DISPLAY_NAME, displayName)
    }

    val cursor = Collect.getInstance().contentResolver.query(formUri, null, null, null, null)
    cursor?.use {
        it.moveToFirst()
        if (displayName == "") {
            val formname = it.getString(it.getColumnIndex(FormsProviderAPI.FormsColumns.DISPLAY_NAME))
            values.put(InstanceProviderAPI.InstanceColumns.DISPLAY_NAME, formname)
        }
        val submissionUri = it.getString(it.getColumnIndex(FormsProviderAPI.FormsColumns.SUBMISSION_URI))
        val jrformid = it.getString(it.getColumnIndex(FormsProviderAPI.FormsColumns.JR_FORM_ID))
        val jrversion = it.getString(it.getColumnIndex(FormsProviderAPI.FormsColumns.JR_VERSION))
        values.put(InstanceProviderAPI.InstanceColumns.SUBMISSION_URI, submissionUri)
        values.put(InstanceProviderAPI.InstanceColumns.JR_FORM_ID, jrformid)
        values.put(InstanceProviderAPI.InstanceColumns.JR_VERSION, jrversion)
    }
    return InstancesDao().saveInstance(values)
}

fun updateFormTitle(instanceUri: Uri, displayName: String) {
    if (displayName != "") {
        val values = ContentValues()
        values.put(InstanceProviderAPI.InstanceColumns.DISPLAY_NAME, displayName)
        Collect.getInstance().contentResolver.update(instanceUri, values, null, null)
    }
}

/**
 * Mark an instance in the InstancesProvider as STATUS_INCOMPLETE
 *
 * @param instanceUri The URI of the instance in the InstancesProvider
 */
fun markStatusIncomplete(instanceUri: Uri) {
    val values = ContentValues()
    values.put(InstanceProviderAPI.InstanceColumns.STATUS, Instance.STATUS_INCOMPLETE)
    Collect.getInstance().contentResolver.update(instanceUri, values, null, null)
}

fun getIdFromFormId(formId: String): Long {
    val cursor = FormsDao().getFormsCursorForFormIdSortedDateDesc(formId)
    return cursor?.use {
        if (it.moveToFirst()) {
            it.getLong(it.getColumnIndex(FormsProviderAPI.FormsColumns._ID))
        } else {
            -1
        }
    } ?: -1
}

fun formIdExists(formId: String) = getIdFromFormId(formId) >= 0

fun getFormIdFromInstanceId(id: Long): String {
    val cursor = InstancesDao().getInstancesCursorForId(id.toString())
    val instances = InstancesDao().getInstancesFromCursor(cursor)
    return instances.firstOrNull()?.jrFormId ?: ""
}

/**
 * Converts an id number to Uri for a form.
 *
 * @param id Id number
 * @return Returns the corresponding FormProvider Uri.
 */
fun getFormUriFromId(id: Long): Uri {
    return Uri.withAppendedPath(FormsProviderAPI.FormsColumns.CONTENT_URI, id.toString())
}

/**
 * Converts an id number to Uri for an instance.
 *
 * @param id Id number
 * @return Returns the corresponding InstanceProvider Uri.
 */
fun getInstanceUriFromId(id: Long): Uri {
    return Uri.withAppendedPath(InstanceProviderAPI.InstanceColumns.CONTENT_URI, id.toString())
}

/**
 * Gets the instance path from a Uri for one instance
 *
 * @param instance A Uri for a single instance (has primary key _ID)
 * @return Returns the path found in the InstanceProvider.
 * @throws SubformException If the InstanceProvider does not have
 * the required information, this exception is thrown.
 */
fun getInstancePath(instance: Uri): String {
    val formInfo = ContentResolverHelper.getFormDetails(instance)
    return formInfo.instancePath
}

fun getInstanceIdFromUri(instance: Uri, instanceFile: File?): Long {
    return if (Collect.getInstance().contentResolver.getType(instance) ==
            InstanceProviderAPI.InstanceColumns.CONTENT_ITEM_TYPE) {
        instance.lastPathSegment?.toLong() ?: -1
    } else if (Collect.getInstance().contentResolver.getType(instance) ==
            FormsProviderAPI.FormsColumns.CONTENT_ITEM_TYPE) {
        getInstanceId(instanceFile)
    } else {
        -1
    }
}

/**
 * Get instance ID based on the instance file.
 *
 * Must be called after an instance is inserted into database, and therefore after
 * a save.
 *
 * @return Returns the record ID in the instances provider or -1 if not found
 */
fun getInstanceId(instanceFile: File?): Long {
    val cursor = InstancesDao().getInstancesCursorForFilePath(instanceFile?.absolutePath)
    return cursor?.use {
        if (it.moveToFirst()) {
            it.getLong(it.getColumnIndex(InstanceProviderAPI.InstanceColumns._ID))
        } else {
            -1
        }
    } ?: -1
}