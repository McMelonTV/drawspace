package space.owork.draw.storage

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Pure bare-repository Git tree builder.
 *
 * Never touches `.git/index` or any working-tree files. Existing untouched blobs keep their
 * exact [ObjectId] references, while only modified/added blobs are inserted into the object store.
 */
internal object BareTreeBuilder {

    fun readAllBlobIds(repo: Repository, commitId: ObjectId?): MutableMap<String, ObjectId> {
        val map = LinkedHashMap<String, ObjectId>()
        if (commitId == null) return map
        RevWalk(repo).use { revWalk ->
            val commit = revWalk.parseCommit(commitId)
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(commit.tree)
                treeWalk.isRecursive = true
                while (treeWalk.next()) {
                    map[treeWalk.pathString] = treeWalk.getObjectId(0)
                }
            }
        }
        return map
    }

    fun readBlobBytes(repo: Repository, objectId: ObjectId): ByteArray =
        repo.open(objectId, Constants.OBJ_BLOB).bytes

    fun writeTree(inserter: ObjectInserter, files: Map<String, ObjectId>): ObjectId {
        val root = DirNode()
        for ((path, blobId) in files) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var cursor = root
            for (i in 0 until parts.lastIndex) {
                cursor = cursor.dirs.getOrPut(parts[i]) { DirNode() }
            }
            cursor.blobs[parts.last()] = blobId
        }
        return formatNode(inserter, root)
    }

    private fun formatNode(inserter: ObjectInserter, node: DirNode): ObjectId {
        val entries = ArrayList<TreeEntry>(node.dirs.size + node.blobs.size)
        for ((name, childDir) in node.dirs) {
            val dirId = formatNode(inserter, childDir)
            entries.add(TreeEntry(name = name, sortKey = "$name/", mode = FileMode.TREE, id = dirId))
        }
        for ((name, blobId) in node.blobs) {
            entries.add(TreeEntry(name = name, sortKey = name, mode = FileMode.REGULAR_FILE, id = blobId))
        }
        // Git canonical tree sorting: compare raw UTF-8 bytes with trailing '/' for subdirectories.
        entries.sortWith { a, b -> compareUnsignedBytes(a.sortKey.encodeToByteArray(), b.sortKey.encodeToByteArray()) }

        val formatter = TreeFormatter()
        for (e in entries) {
            formatter.append(e.name, e.mode, e.id)
        }
        return inserter.insert(formatter)
    }

    private fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private class DirNode {
        val dirs = LinkedHashMap<String, DirNode>()
        val blobs = LinkedHashMap<String, ObjectId>()
    }

    private data class TreeEntry(
        val name: String,
        val sortKey: String,
        val mode: FileMode,
        val id: ObjectId,
    )
}
