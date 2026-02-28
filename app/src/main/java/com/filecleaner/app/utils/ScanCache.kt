package com.filecleaner.app.utils

import android.content.Context
import com.filecleaner.app.data.DirectoryNode
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ScanCache {

    private const val CACHE_FILE = "scan_cache.json"

    suspend fun save(context: Context, files: List<FileItem>, tree: DirectoryNode) =
        withContext(Dispatchers.IO) {
            val root = JSONObject()

            // Serialize file list
            val filesArray = JSONArray()
            for (item in files) {
                filesArray.put(fileItemToJson(item))
            }
            root.put("files", filesArray)

            // Serialize directory tree
            root.put("tree", directoryNodeToJson(tree))

            // Write to file
            val cacheFile = File(context.filesDir, CACHE_FILE)
            cacheFile.writeText(root.toString())
        }

    suspend fun load(context: Context): Pair<List<FileItem>, DirectoryNode>? =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, CACHE_FILE)
            if (!cacheFile.exists()) return@withContext null

            try {
                val root = JSONObject(cacheFile.readText())

                val filesArray = root.getJSONArray("files")
                val files = mutableListOf<FileItem>()
                for (i in 0 until filesArray.length()) {
                    val item = jsonToFileItem(filesArray.getJSONObject(i))
                    // Validate file still exists on disk — prevents ghost entries
                    if (File(item.path).exists()) {
                        files.add(item)
                    }
                }

                val tree = pruneDeletedFiles(jsonToDirectoryNode(root.getJSONObject("tree")))

                Pair(files, tree)
            } catch (e: Exception) {
                // Corrupt cache — delete and return null
                cacheFile.delete()
                null
            }
        }

    /**
     * Recursively prune files that no longer exist on disk from the directory tree.
     * Recalculates totalSize and totalFileCount after pruning.
     */
    private fun pruneDeletedFiles(node: DirectoryNode): DirectoryNode {
        val validFiles = node.files.filter { File(it.path).exists() }
        val prunedChildren = node.children.map { pruneDeletedFiles(it) }.toMutableList()

        val ownFileSize = validFiles.sumOf { it.size }
        val ownFileCount = validFiles.size
        val totalSize = ownFileSize + prunedChildren.sumOf { it.totalSize }
        val totalFileCount = ownFileCount + prunedChildren.sumOf { it.totalFileCount }

        return node.copy(
            files = validFiles,
            children = prunedChildren,
            totalSize = totalSize,
            totalFileCount = totalFileCount
        )
    }

    private fun fileItemToJson(item: FileItem): JSONObject = JSONObject().apply {
        put("path", item.path)
        put("name", item.name)
        put("size", item.size)
        put("lastModified", item.lastModified)
        put("category", item.category.name)
        put("duplicateGroup", item.duplicateGroup)
    }

    private fun jsonToFileItem(json: JSONObject): FileItem = FileItem(
        path = json.getString("path"),
        name = json.getString("name"),
        size = json.getLong("size"),
        lastModified = json.getLong("lastModified"),
        category = try {
            FileCategory.valueOf(json.getString("category"))
        } catch (_: Exception) {
            FileCategory.OTHER
        },
        duplicateGroup = json.optInt("duplicateGroup", -1)
    )

    private fun directoryNodeToJson(node: DirectoryNode): JSONObject = JSONObject().apply {
        put("path", node.path)
        put("name", node.name)
        put("totalSize", node.totalSize)
        put("totalFileCount", node.totalFileCount)
        put("depth", node.depth)

        val filesArray = JSONArray()
        for (file in node.files) {
            filesArray.put(fileItemToJson(file))
        }
        put("files", filesArray)

        val childrenArray = JSONArray()
        for (child in node.children) {
            childrenArray.put(directoryNodeToJson(child))
        }
        put("children", childrenArray)
    }

    private fun jsonToDirectoryNode(json: JSONObject): DirectoryNode {
        val filesArray = json.getJSONArray("files")
        val files = mutableListOf<FileItem>()
        for (i in 0 until filesArray.length()) {
            files.add(jsonToFileItem(filesArray.getJSONObject(i)))
        }

        val childrenArray = json.getJSONArray("children")
        val children = mutableListOf<DirectoryNode>()
        for (i in 0 until childrenArray.length()) {
            children.add(jsonToDirectoryNode(childrenArray.getJSONObject(i)))
        }

        return DirectoryNode(
            path = json.getString("path"),
            name = json.getString("name"),
            files = files,
            children = children,
            totalSize = json.getLong("totalSize"),
            totalFileCount = json.getInt("totalFileCount"),
            depth = json.getInt("depth")
        )
    }
}
