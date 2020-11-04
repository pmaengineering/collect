package org.odk.collect.android.subform

import android.net.Uri
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.javarosa.xform.util.XFormUtils
import org.odk.collect.android.application.Collect
import org.odk.collect.android.dao.helpers.ContentResolverHelper
import org.odk.collect.android.external.ExternalDataManagerImpl
import org.odk.collect.android.external.handler.ExternalDataHandlerPull
import org.odk.collect.android.formentry.loading.FormInstanceFileCreator
import org.odk.collect.android.javarosawrapper.FormController
import org.odk.collect.android.provider.InstanceProviderAPI
import org.odk.collect.android.storage.StoragePathProvider
import org.odk.collect.android.tasks.FormLoaderTask
import org.odk.collect.android.tasks.SaveFormToDisk
import org.odk.collect.android.utilities.FileUtils
import org.odk.collect.android.utilities.FormDefCache
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import timber.log.Timber
import java.io.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory

/**
 * Modifies the parent form if a paired node with child form is changed.
 *
 * After opening each file, this method gets all node pairs, or
 * mappings, and loops through them. For each mapping, the instance value
 * is obtained in both files. If there is a difference, then the parent
 * is modified in memory. If there is any change in the parent, then the
 * file is rewritten to disk. If there is an exception while evaulating
 * xpaths or doing anything else, updating the parent form is aborted.
 *
 * If the parent form is changed, then its status is changed to
 * incomplete.
 *
 * @param childId Instance id
 * @param dryRun If true, then this does not mutate any files or objects.
 * @return Returns true if a parent form is updated, false if not.
 */
fun manageParentForm(childId: Long, dryRun: Boolean = false): SubformActionResult {
    Timber.i("Inside manage Parent Form for form $childId, dry run = $dryRun")
    val nodeMappings = getMappingsToParent(childId)
    if (nodeMappings.isEmpty()) return SubformActionResult() // no parent form found, no document updated
    val childInstancePath = getInstancePath(getInstanceUriFromId(childId))
    val parentId = nodeMappings.first().parentId
    Timber.i("Child form $childId has parent form $parentId")
    Timber.i("Mappings: $nodeMappings")
    val parentUri = getInstanceUriFromId(parentId)
    val parentInstancePath = getInstancePath(parentUri)

    val childDocument = getDocument(childInstancePath)
    val parentDocument = getDocument(parentInstancePath)

    val xpath = XPathFactory.newInstance().newXPath()
    val mappedNodeUpdated = nodeMappings.map {
        Timber.i("Working with node $it")
        // todo: wrap in try catch block and catch errors
        try{
            val childExpression = xpath.compile(it.childNode)
            val childNode = childExpression.evaluate(childDocument, XPathConstants.NODE) as Node
            val childValue = childNode.textContent
            val parentExpression = xpath.compile(it.parentNode)
            val parentNode = parentExpression.evaluate(parentDocument, XPathConstants.NODE) as Node
            val parentValue = parentNode.textContent
            if (childValue != parentValue) {
                Timber.i("Parent form at ${it.parentNode} has value $parentValue. Updated to match value $childValue from ${it.childNode}")
                parentNode.textContent = childValue
                true
            } else {
                Timber.i("Parent form at ${it.parentNode} has value $parentValue, which matches value $childValue from ${it.childNode}")
                false
            }
        } catch (e: Exception) {
            false
        }

    }
    return if (mappedNodeUpdated.any{ it }) {
        if ( !dryRun ) {
            writeDocumentToFile(parentDocument, parentInstancePath)
            markStatusIncomplete(parentUri)
        }
        SubformActionResult(updated=1)
    } else {
        SubformActionResult()
    }
}

/**
 *                   saveForm directive
 *  subform          isRelevant |  !isRelevant
 *                 ---------------------------
 *  exists         |    ***     |   delete child form
 *                 ---------------------------
 *  does not exist |   create   |   do nothing
 *                 ---------------------------
 *
 *  ***   if the saveForm attrValue matches the subform's formId, do nothing,
 *        else delete the subform
 */
fun deleteNecessaryChildForms(parentId: Long, directivesByRepeatIndex: Map<Int, List<SubformDirective>>, dryRun: Boolean = false): SubformActionResult {
    var subformActionResult = SubformActionResult()
    val whichChildFormsToDelete = getWhichChildFormsToDelete(parentId, directivesByRepeatIndex)
    if ( dryRun ) {
        return SubformActionResult(deleted=whichChildFormsToDelete.size)
    }
    for (child in whichChildFormsToDelete) {
        subformActionResult += deleteInstanceAndChildren(child)
    }
    return subformActionResult
}

/**
 * Get a list of child instance IDs to delete
 */
fun getWhichChildFormsToDelete(parentId: Long, directivesByRepeatIndex: Map<Int, List<SubformDirective>>): List<Long> {
    if (parentId < 0) return emptyList()
    val childInstanceIds = mutableListOf<Long>()
    for ((repeatIndex, directives) in directivesByRepeatIndex) {
        val childInstanceId = getChild(parentId, repeatIndex.toLong())
        val saveForm = directives.firstOrNull { it.attrName == SAVE_FORM }
        if (childInstanceId >= 0 && (saveForm == null || !saveForm.isRelevant)) {
            childInstanceIds.add(childInstanceId)
            Timber.d("Determined child $childInstanceId at index $repeatIndex should be deleted because saveForm is not relevant")
        } else if (childInstanceId >= 0 && saveForm != null) {
            val childFormId = getFormIdFromInstanceId(childInstanceId)
            if (childFormId != saveForm.attrValue) {
                childInstanceIds.add(childInstanceId)
                Timber.d("Determined child $childInstanceId at index $repeatIndex should be deleted because saveForm form_id ${saveForm.attrValue} does not match child's form_id $childFormId")
            }
        }
    }
    return childInstanceIds
}

/**
 * Using the data from traversal, creates/updates children forms.
 *
 * During traversal, the largest repeat index is stored. From 1 up to and
 * including the largest repeat index, the saveForm and saveInstance
 * information is collected. If this information is not empty, then the
 * Uri or the child instance is obtained (perhaps creating the child
 * first). Everything is sent to the subroutine `insertAllIntoChild` to
 * finish off transferring parent information to the child. Raised
 * exceptions abort the process.
 *
 * @return Returns number of child forms that are modified ( >= 0) or an
 * error code.
 */
fun outputAndUpdateChildForms(parentId: Long, directivesByRepeatIndex: Map<Int, List<SubformDirective>>, dryRun: Boolean = false): SubformActionResult {
    var subformActionResult = SubformActionResult()
    for ((repeatIndex, directives) in directivesByRepeatIndex) {
        val saveFormDirective = directives.firstOrNull { it.attrName == SAVE_FORM && it.isRelevant }
        if (saveFormDirective == null) {
            Timber.i("At index $repeatIndex, and no relevant saveForm directive found!")
            continue
        }
        val childId = getChild(parentId, repeatIndex.toLong())
        Timber.i("At index $repeatIndex, and child instance ID is $childId")
        val thisResult = if (childId < 0) SubformActionResult(created=1) else SubformActionResult()
        if ( dryRun && childId < 0 ) {
            subformActionResult += thisResult
            continue
        }
        val isModified = try {
            val childInstance = getOrCreateChildForm(childId, saveFormDirective)
            val saveInstances = directives.filter { it.attrName == SAVE_INSTANCE && it.isRelevant }
            insertAllIntoChild(saveInstances, childInstance, parentId, dryRun)
        } catch (ex: Exception) {
            when(ex) {
                is FileNotFoundException, is TransformerException -> {
                    Timber.w("Unable to save document for repeatIndex $repeatIndex")
                }
                is ParserConfigurationException, is SAXException, is IOException -> {
                    Timber.w("Unable to open document for repeatIndex $repeatIndex")
                }
                is SaveFormIdNotFound -> {
                    Timber.w("At repeatIndex $repeatIndex, unable to create instance with form id ${ex.formId}")
                    subformActionResult += SubformActionResult(errorCode=SAVE_FORM_ID_NOT_MISSING, errorData=ex.formId)
                }
                is SaveInstanceXPathNotFound -> {
                    Timber.w("At repeatIndex $repeatIndex, unable to save to XPath: ${ex.xpath}")
                    subformActionResult += SubformActionResult(errorCode= SAVE_INSTANCE_XPATH_NOT_FOUND, errorData=ex.xpath)
                }
            }
            false
        }
        subformActionResult += if (isModified && thisResult.created == 0) {
            SubformActionResult(updated=1)
        } else {
            thisResult
        }
    }
    return subformActionResult
}

/**
 * Gets child form or creates one if it doesn't exist.
 *
 * The child form is looked up based on the parentId (ID in the instances database)
 * and the repeatIndex (-1 if not in a repeat). The directives are passed in just
 * in case the child form needs to be created.
 */
fun getOrCreateChildForm(childId: Long, saveFormDirective: SubformDirective): Uri {
    return if (childId < 0) {
        val formId = saveFormDirective.attrValue
        val formsProviderId = getIdFromFormId(formId)
        if (formsProviderId < 0) throw SaveFormIdNotFound(formId)
        val formUri = getFormUriFromId(formsProviderId)
        val formTitle = saveFormDirective.nodeValue
        Timber.d("Creating $formId form with title $formTitle")
        createInstance(formUri, formTitle)
    } else {
        getInstanceUriFromId(childId).also{
            Timber.d("Using linked form at $it")
            // updateFormTitle(it, saveFormDirective.nodeValue)
        }

    }
}

/**
 * Create an instance from a form definition Uri.
 *
 * In order to get the proper instance, we must first load the child form,
 * then save it to disk and only *then* can we edit the .xml file with the
 * appropriate values from the parent form. Much of this is similar to
 * what happens in FormLoaderTask, but we're already in a thread here.
 *
 * Unfortunately, we must violate DRY (don't repeat yourself). Normally,
 * creating an instance from a Uri is done at the end of onCreate in
 * FormEntryActivity and in doInBackground of FormLoaderTask, not in a
 * method that can be called. This is a lot of copy and paste.
 *
 * @param formUri A Uri of a form in the FormProvider.
 * @return Returns the Uri of the newly created instance.
 * @throws IOException Routine aborted if there is an IO error.
 */
fun createInstance(formUri: Uri, displayName: String): Uri {
    Timber.d("Creating instance with formUri $formUri and display name $displayName")
    // from FormEntryActivity#loadFromIntent
    val formPath = ContentResolverHelper.getFormPath(formUri)
    // from FormLoaderTask#doInBackground
    val formXml = File(formPath)
    // check for Cached version. Otherwise get from scratch
    val formDef = FormDefCache.readCache(formXml) ?: XFormUtils
            .getFormFromInputStream(FileInputStream(formXml))
            .also{
                FormDefCache.writeCache(it, formPath)
            }

    val formFileName = formXml.name.substring(0, formXml.name.lastIndexOf("."))
    val formMediaDir = File(formXml.parent, "$formFileName-media")

    val externalDataManager = ExternalDataManagerImpl(formMediaDir)
    val externalDataHandlerPull = ExternalDataHandlerPull(
            externalDataManager)
    formDef.evaluationContext.addFunctionHandler(externalDataHandlerPull)
    FormLoaderTask.processItemSets(formMediaDir)
    // TODO: external data handling. See FormLoaderTask#loadExternalData(formMediaDir)
    // TODO: It is possible that without it, pulldata / search will not work on other .csv files.
    // FormLoaderTask#initializeForm()
    formDef.initialize(true, InstanceInitializationFactory())
    // FormLoaderTask#doInBackground
    val fem = FormEntryModel(formDef)
    val fec = FormEntryController(fem)
    val formInstanceFileCreator = FormInstanceFileCreator(StoragePathProvider(), System::currentTimeMillis)
    val instancePath = formInstanceFileCreator.createInstanceFile(formPath)
    val controller = FormController(formMediaDir, fec, instancePath)
    exportData(controller)
    return updateInstanceDatabase(formUri, instancePath.toString(), displayName)
}

/**
 * Writes data in an instance to disk.
 *
 * Pre-condition: The formController must have its instanceFile
 * property set.
 *
 * @param formController The form controller for an instance
 * @throws IOException An IO error aborts the routine.
 */
fun exportData(formController: FormController) {
    val payload = formController.filledInFormXml
    val instancePath = formController.instanceFile?.absolutePath
    Timber.d("Exporting data from form controller to $instancePath")
    SaveFormToDisk.writeFile(payload, instancePath)
}

/**
 * Iterates through SubformDirectives for insertion into a child form.
 *
 * First, the InstanceProvider is updated to show the correct
 * instanceName. Then for each item in saveInstanceMapping,
 * insertIntoChild copies the new information if necessary. Binary data is
 * copied if necessary. If saveInstanceMapping returns true, i.e. if the
 * child form is changed, then the child form is written to disk and its
 * status is set to incomplete.
 *
 * If an error is raised inside insertIntoChild, then that morsel of
 * traversal data is skipped and the next is processed. Other errors abort
 * the routine.
 *
 * @param directives The relevant directives for this child instance.
 * @param childInstance The Uri for the child instance
 * @return Returns true if and only if a child is updated.
 */
fun insertAllIntoChild(directives: List<SubformDirective>, childInstance: Uri, parentId: Long, dryRun: Boolean = false): Boolean {
    Timber.d("Insert all into child: ${directives.size} directive(s) into $childInstance")
    var isModified = false
    val childInstancePath = getInstancePath(childInstance)
    val document = getDocument(childInstancePath)
    for (directive in directives) {
        try {
            val thisModified = insertIntoChild(directive, document)
            if ( !dryRun ) {
                checkCopyBinaryFile(directive, childInstancePath, parentId)
                updateRelationsDatabase(directive, childInstance, parentId)
            }
            isModified = isModified || thisModified
        } catch (e: XPathExpressionException) {
            Timber.w("Unable to insert value \"${directive.nodeValue}\" into child XPath \"${directive.attrValue}\"")
            throw SaveInstanceXPathNotFound(directive.attrValue)
        }
    }
    if ( isModified && !dryRun ) {
        writeDocumentToFile(document, childInstancePath)
        markStatusIncomplete(childInstance)
    }
    return isModified
}

/**
 * Inserts data into child form from one morsel SubformDirective.
 *
 * @param directive A `saveInstance` morsel of SubformDirective data.
 * @param document The child form represented as a document.
 * @return Returns true if and only if the child instance is modified.
 * @throws XPathExpressionException If the XPaths do not compile or
 * find a node this routine is aborted.
 */
private fun insertIntoChild(directive: SubformDirective, document: Document): Boolean {
    val parentValue = if (directive.isRelevant) directive.nodeValue else ""
    val xpath = XPathFactory.newInstance().newXPath()
    val childInstanceXpath = directive.attrValue
    val expression = xpath.compile(childInstanceXpath)
    val childNode = expression.evaluate(document, XPathConstants.NODE) as Node
    val childValue = childNode.textContent
    // TODO: How to figure out what happens with a bad XPath? If so throw error (SaveInstanceXPathNotFound) and catch in
    return if (childValue != parentValue) {
        Timber.i("Child form at $childInstanceXpath has value $childValue. Updated to match value $parentValue from ${directive.nodeXPath}")
        childNode.textContent = parentValue
        true
    } else {
        Timber.i("Child form at $childInstanceXpath has value $childValue, which matches value $parentValue from ${directive.nodeXPath}")
        false
    }
}

fun updateRelationsDatabase(directive: SubformDirective, childInstance: Uri, parentId: Long) {
    val childId = childInstance.lastPathSegment?.toString() ?: "-1" // TODO: raise exception
    insert(parentId.toString(), directive.nodeXPath, directive.repeatableNode,
            directive.repeatIndex.toString(), childId, directive.attrValue, ignore=true)
}

/**
 * Gets a Document object for a given instance path.
 *
 * @param path The path to the instance
 * @return Returns a Document object for the file at the supplied path.
 * @throws ParserConfigurationException One of various exceptions that
 * abort the routine.
 * @throws SAXException One of various exceptions that abort the routine.
 * @throws IOException One of various exceptions that abort the routine.
 */
@Throws(ParserConfigurationException::class, SAXException::class, IOException::class)
fun getDocument(path: String): Document {
    Timber.d("Getting document from $path")
    val inputFile = File(path)
    val inputStream = FileInputStream(inputFile)
    val reader = InputStreamReader(inputStream, "UTF-8")
    val inputSource = InputSource(reader)
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputSource)
    inputStream.close()
    reader.close()
    return document
}

/**
 * Writes a Document object to the supplied path.
 *
 * @param document The document object
 * @param path The output path
 * @throws FileNotFoundException An exception that aborts writing to disk
 * @throws TransformerException An exception that aborts writing to disk
 */
private fun writeDocumentToFile(document: Document, path: String) {
    Timber.d("Writing document to $path")
    // there's a bug in streamresult that replaces spaces in the
    // filename with %20
    // so we use a fileoutput stream
    // http://stackoverflow.com/questions/10301674/save-file-in-android-with-spaces-in-file-name
    val outputFile = File(path)
    val fos = FileOutputStream(outputFile)
    val output = StreamResult(fos)
    val input = DOMSource(document)
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.transform(input, output)
    fos.close()
}

/**
 * This function deletes the instance and its children in the content provider and
 * in the form relations database.
 */
fun deleteInstanceAndChildren(instanceId: Long, dryRun: Boolean = false): SubformActionResult {
    Timber.i("Inside deleteInstanceAndChildren")
    var subformActionResult = SubformActionResult()
    if (instanceId < 0) {
        return subformActionResult
    }
    val children = getChildren(instanceId)
    if ( dryRun ) {
        return SubformActionResult(deleted=(children.size + 1))
    }
    deleteAsParent(instanceId)
    deleteAsChild(instanceId)
    val allIdsToDelete = children.map { it.toString() } + instanceId.toString()
    allIdsToDelete.forEach {
        val deleteForm = Uri.withAppendedPath(InstanceProviderAPI.InstanceColumns.CONTENT_URI, it)
        val wasDeleted = Collect.getInstance().contentResolver.delete(deleteForm, null, null)
        subformActionResult += SubformActionResult(deleted=wasDeleted)
    }
    Timber.i("deleteInstanceAndChildren (dry run = $dryRun) returning: $subformActionResult")
    return subformActionResult
}

/**
 * Checks the value of a node, and if binary, the file is copied to child
 *
 * This check is performed on all data that is copied from parent to
 * child.
 *
 * @param directive Traversal data for the current node
 * @param childInstancePath Path to the child instance save on disk
 * @param parentId the ID of the parent form
 * @return Returns true if and only if `copyBinaryFile` returns true.
 * @throws SubformException An exception allowed to propagate from
 * subroutines in order to abort checking/copying.
 */
fun checkCopyBinaryFile(directive: SubformDirective, childInstancePath: String, parentId: Long): Boolean {
    var toReturn = false
    val childInstanceValue = directive.nodeValue
    val suffices = listOf(".jpg", ".jpeg", ".3gpp", ".3gp", ".mp4", ".png") // check for more extensions?
    if (suffices.any { directive.nodeValue.endsWith(it) }) {
        val parentInstance = getInstanceUriFromId(parentId)
        val parentInstancePath = getInstancePath(parentInstance)
        toReturn = copyBinaryFile(parentInstancePath, childInstancePath, childInstanceValue)
    }
    return toReturn
}

/**
 * Copies a binary file from one instance to another.
 *
 * This method accepts paths to instances. The enclosing directories are
 * determined, from which appropriate source and destination paths are
 * generated for the file to be copied.
 *
 * @param parentInstancePath The path to the instance of the parent.
 * @param childInstancePath The path to the instance of the child.
 * @param filename The file name of the file to be copied.
 * @return Returns true if everything happens without a hitch.
 */
fun copyBinaryFile(parentInstancePath: String, childInstancePath: String, filename: String): Boolean {
    val parentFile = File(parentInstancePath)
    val childFile = File(childInstancePath)
    val parentImage = File(parentFile.parent + "/" + filename)
    val childImage = File(childFile.parent + "/" + filename)
    Timber.i("copyBinaryFile $filename from  ${parentFile.parent} to ${childFile.parent}")
    FileUtils.copyFile(parentImage, childImage)
    return true
}