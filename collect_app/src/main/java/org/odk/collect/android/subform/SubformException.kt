package org.odk.collect.android.subform

sealed class SubformException : Exception()

class SaveFormIdNotFound(val formId: String) : SubformException()

class SaveInstanceXPathNotFound(val xpath: String) : SubformException()

class InstancePathNotFound : SubformException()

@JvmField
val SAVE_INSTANCE_XPATH_NOT_FOUND = -1
@JvmField
val SAVE_FORM_ID_NOT_MISSING = -2