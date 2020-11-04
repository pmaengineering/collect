package org.odk.collect.android.subform

import android.net.Uri
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.instance.TreeElement
import timber.log.Timber
import java.io.File
import kotlin.math.max

const val SAVE_INSTANCE = "saveInstance"
const val SAVE_FORM = "saveForm"
const val DELETE_FORM = "deleteForm"

/*
Subform data
need to know the repeat (or not repeat), this is the key.
need to know the saveInstances, their XPaths (attrValue) and their node values
need to know the saveForm formId (attrValue) and the node value (Form Title). If it is relevant.
need to know if there is a relevant delete form (for child form)
Repeat Index should be the correct value if it is a repeatable node. Otherwise, it is -1
TEST SCENARIOS:
- Hit save at very end.
- No SAVE_FORM, but yes SAVE_INSTANCEs in a repeat
- Two SAVE_FORMs in a repeat
- SAVE_FORM not in a repeat
- Two SAVE_FORMs not in a repeat
- SAVE_FORM changes from one save point to the next
- SAVE_INSTANCE has incorrect XPath: This leads to a TODO
- SAVE_FORM does not exist on the phone
- updateParent: Xpath in database is for not-relevant item
- updateParent: one of the documents isn't found
- updateParent: updating parent should mark it as incomplete
- updateParent: check that updating multiple values works (worried about filter)
Maybe there should be distinct steps: make sure the correct subforms are in place first,
then update the values.
 */

/**
 * instanceFile in FormController is set only in FormEntryActivity#loadingComplete,
 * after FormLoaderTask finishes. Therefore, when the SubformManager instance is
 * initialized in FormController, instanceFile will be null, but shortly thereafter it is set.
 */
class SubformManager(val formDef: FormDef, var instanceFile: File?) {
    val formInstance = formDef.instance
    var instanceId: Long = -1
    // TODO: perhaps instanceFile should be a var next to instanceId. Seems like same situation
    val currentSubformDirectives: List<SubformDirective>
        get() {
            val directives = mutableListOf<SubformDirective>()
            val root = formInstance.root
            discoverDirectives(root, directives)
            return directives.toList()
        }
    val directivesOnCreation = currentSubformDirectives
    val needsSubformManagement = directivesOnCreation.any {
        it.attrName == SAVE_FORM
    }
    val needsDeleteFormCheck = directivesOnCreation.any {
        it.attrName == DELETE_FORM
    }
    val instanceSaveForms = directivesOnCreation.filter {
        it.attrName == SAVE_FORM
    }.map {
        it.attrValue
    }.sorted().distinct()
    val missingSaveForms = instanceSaveForms.filterNot {
        formIdExists(it)
    }
    val shouldDeleteThisForm: Boolean
        get() {
            return currentSubformDirectives.any {
                it.attrName == DELETE_FORM && it.isRelevant
            }
        }

    /**
     * Used in code to check for deletions.
     */
    fun doManageDryRun(uri: Uri?) = manageFormRelations(uri ?: Uri.EMPTY, true)

    fun missingSaveFormsList() = missingSaveForms.joinToString()
    fun hasSaveFormAndDeleteForm() = needsSubformManagement && needsDeleteFormCheck
    /**
     * Should return how many forms were updated, how many were deleted, and how many created.
     */
    @Synchronized fun manageFormRelations(uri: Uri, dryRun: Boolean = false): SubformActionResult {
        var subformActionResult = SubformActionResult()
        if (instanceId < 0) {
            instanceId = getInstanceIdFromUri(uri, instanceFile)
        }
        Timber.d("Inside manageFormRelations for instance $instanceId")
        subformActionResult += manageParentForm(instanceId, dryRun)
        if (needsDeleteFormCheck && shouldDeleteThisForm) {
            Timber.d("Deleting this instance and its children.")
            subformActionResult += deleteInstanceAndChildren(instanceId, dryRun)
            subformActionResult += SubformActionResult(selfDestruct=true)
        } else if (needsSubformManagement) {
            Timber.d("Managing subforms for instance $instanceId")
            val directivesByRepeatIndex = getDirectivesByRepeatIndex()
            subformActionResult += deleteNecessaryChildForms(instanceId, directivesByRepeatIndex, dryRun)
            subformActionResult += outputAndUpdateChildForms(instanceId, directivesByRepeatIndex, dryRun)
        }
        Timber.d("Result of manageFormRelations: $subformActionResult")
        return subformActionResult
    }

    /**
     * Manage necessary database transactions and deletions.
     */
    @Synchronized fun manageRepeatDelete(repeatIndex: Int, dryRun: Boolean = false): SubformActionResult {
        val parentId = instanceId
        Timber.i("Inside manageRepeatDelete for instance $parentId, group $repeatIndex (dry run: $dryRun)")
        return if (parentId >= 0 && repeatIndex >= 0) {
            val childInstanceId = getChild(parentId, repeatIndex.toLong())
            deleteInstanceAndChildren(childInstanceId, dryRun).also {
                Timber.i("Returning $it (dryRun=$dryRun)")
                if ( !dryRun ) {
                    updateRepeatSiblingInfo(parentId, repeatIndex.toLong())
                }
            }
        } else {
            Timber.i("manageRepeatDelete returning empty SubformActionResult")
            SubformActionResult()
        }
    }

    /**
     * Check if repeat has a subform.
     */
    fun repeatHasSubform(repeatIndex: Int) = (getChild(instanceId, repeatIndex.toLong()) >= 0)

    /**
     * Get how many to delete
     */
    fun getDeleteInfo(): SubformDeleteInfo {
        val whichToDelete = getWhichChildFormsToDelete(instanceId, getDirectivesByRepeatIndex())
        val selfDestruct = instanceId in whichToDelete
        val deleteCount = whichToDelete.size
        return SubformDeleteInfo(selfDestruct, deleteCount)
    }

    /**
     * Make a map of the directives.
     *
     * Key is the repeatIndex and values are a list of directives with that repeatIndex.
     */
    private fun getDirectivesByRepeatIndex() = currentSubformDirectives
            .filter{ !it.isTemplate }.groupBy { it.repeatIndex }

    /**
     * Remove initial (form-definition-name) from full tree element reference.
     *
     * A tree element reference looks like instance(form-id-v10)/root/xpath[1]/to[1]/node[1]
     *
     * We want just /root/xpath[1]/to[1]/node[1]
     */
    private fun String.refToXPath(): String = substring(max(indexOf("/"),0))

    /**
     * Traverses an instance and collects all form relations information.
     *
     * This is a recursive method that traverses a tree in a depth-first
     * search. It keeps a record of the nearest repeatable node as it goes. It
     * scans all attributes for all nodes in this search.
     *
     * When a subform directive is found, a SubformDirective object is created
     * to store that information and saved to the search results.
     *
     * @param te The current tree element
     * @param directives The mutable list where we store traverse data
     * @param repeatableNode The most recent repeatable node. It is null if
     *                       there is no repeatable ancestor node
     */
    private fun discoverDirectives(te: TreeElement, directives: MutableList<SubformDirective>,
                                   repeatableNode: String? = null) {
        if (te.hasChildren()) {
            for (i in 0 until te.numChildren) {
                val child = te.getChildAt(i)
                val nodeXPath = child.getRef().toString(true).refToXPath()
                val updatedRepeatableNode = if (child.isRepeatable()) nodeXPath else repeatableNode
                val directivesFromAttrs = checkAttrs(child, nodeXPath, repeatableNode)
                directives += directivesFromAttrs
                discoverDirectives(child, directives, updatedRepeatableNode)
            }
        }
    }

    /**
     * Checks attributes of a node for subform directives.
     *
     * @param te The current tree element
     * @param nodeXPath The XPath to this tree element. Used to create SubformDirective.
     * @param repeatableNode The most recent repeatable node. Used to create SubformDirective.
     * @return Returns a list of subform directives found for the
     * supplied tree element. If no directives are found, then an
     * empty list is returned.
     */
    private fun checkAttrs(te: TreeElement, nodeXPath: String, repeatableNode: String?): List<SubformDirective> =
            te.bindAttributes.filter {
                it.name in setOf(SAVE_FORM, SAVE_INSTANCE, DELETE_FORM)
            }.map {
                val attrName = it.name
                val attrValue = it.attributeValue
                val nodeValue = te.value?.displayText ?: ""
                val isRelevant = te.isRelevant
                SubformDirective(attrName=attrName, attrValue=attrValue, nodeXPath=nodeXPath,
                        nodeValue=nodeValue, isRelevant=isRelevant, repeatableNode=repeatableNode)
            }
}

/**
 * A data class to store information about a subform directive.
 *
 * The attrName is one of SAVE_INSTANCE, SAVE_FORM, or DELETE_FORM.
 */
data class SubformDirective(val attrName: String, val attrValue: String, val nodeXPath: String,
                            val nodeValue: String, val isRelevant: Boolean,
                            val repeatableNode: String?) {
    val isTemplate = nodeXPath.contains("@template")
    val repeatIndex = if (repeatableNode != null) nodeXPath.parseRepeatIndexFromXPath() else -1

    /**
     * Gets the largest child selector number in an xpath.
     *
     * From examination, xpaths have child selectors at each step of xpath
     * after root, i.e. /root/path[1]/to[1]/node[1]... etc. If any of the
     * child selectors are greater than one, then it must be a repeat group.
     * Usually children are created with the information from a repeat group.
     *
     * This method picks out the greatest child selector in the supplied
     * xpath.
     *
     * If there is more than one non-"1" child selector, then it is a repeat
     * within a repeat. This is bad design.
     *
     * @return Returns a number greater than zero or -1 if there are no
     * indices found
     */
    private fun String.parseRepeatIndexFromXPath(): Int {
        val regex = """\[(\d+)]""".toRegex()
        val matchResults = regex.findAll(this)
        val maxIndex = matchResults.map {
            it.groups[1]?.value?.toInt()
        }.filterNotNull().max()
        return maxIndex ?: -1
    }
}

data class SubformActionResult(val created: Int = 0, val updated: Int = 0, val deleted: Int = 0, val selfDestruct: Boolean = false, val errorCode: Int = 0, val errorData: String = "") {

    fun noAction(): Boolean = this == SubformActionResult()

    operator fun plus(other: SubformActionResult): SubformActionResult {
        val (newErrorCode, newErrorData) = if (other.errorCode < errorCode) {
            other.errorCode to other.errorData
        } else {
            errorCode to errorData
        }
        return SubformActionResult(created + other.created, updated + other.updated,
                deleted + other.deleted, selfDestruct || other.selfDestruct,
                newErrorCode, newErrorData)
    }
}

data class SubformDeleteInfo(val selfDestruct: Boolean, val deleteCount: Int)

/**
 * A class to summarize the subforms on the device
 */
class SubformDeviceSummary() {
    val allRelations = getAllRelations()
    val allParents = allRelations.map { it.first }.toSet()
    val allChildren = allRelations.map { it.second }.toSet()
    val parentToChildren = allRelations.groupBy({it.first}, {it.second})
    val childToParents = allRelations.groupBy({it.second}, {it.first})

    override fun toString(): String {
        return allRelations.map {
            StringBuilder().append(it.first).append(" -> ").append(it.second)
        }.joinToString(prefix= "[\n", postfix = "\n]", separator = "\n")
    }
}