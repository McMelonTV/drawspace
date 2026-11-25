package space.owork.draw.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.CommandStack
import space.owork.draw.engine.DocumentCommand
import space.owork.draw.engine.PartialEraser
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.Ulid
import java.io.File

class GitWorkspaceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `workspace stores multiple documents with bare git history and github previews`() = runTest {
        val dir = tempFolder.newFolder("ws1")
        GitWorkspaceStore(dir).use { store ->
            store.initialize()

            val infDoc = store.createDocument("Infinite Ideas", CanvasMode.Infinite)
            val fixedDoc = store.createDocument("Poster", CanvasMode.Fixed(1920, 1080, 144))

            val layerId = infDoc.primaryStrokeLayer().id
            val s1 = Stroke(
                id = Ulid.next(),
                brush = BrushSpec.defaultFor(ToolType.PEN),
                points = listOf(StrokePoint(0f, 0f), StrokePoint(120f, 80f)),
            )
            val (afterS1, delta1) = DocumentCommand.AddStroke(layerId, s1).apply(infDoc)
            store.flushDelta(infDoc.id, delta1)
            val cp1 = store.checkpoint(afterS1, label = "Initial ink", isNamedSnapshot = true)

            val s2 = Stroke(
                id = Ulid.next(),
                brush = BrushSpec.defaultFor(ToolType.MARKER, 0xFFD93848.toInt()),
                points = listOf(StrokePoint(50f, 10f), StrokePoint(200f, 150f)),
            )
            val (afterS2, delta2) = DocumentCommand.AddStroke(layerId, s2).apply(afterS1)
            store.flushDelta(infDoc.id, delta2)
            store.checkpoint(afterS2, label = "Added marker")

            // Verify document list & latest state
            val docs = store.listDocuments()
            assertEquals(2, docs.size)
            val loadedLatest = store.loadDocument(infDoc.id)
            assertEquals(2, loadedLatest.visibleStrokes().size)

            // Time-travel to earlier checkpoint without touching working tree
            val loadedEarlier = store.loadDocumentAtCheckpoint(infDoc.id, cp1.id)
            assertEquals(1, loadedEarlier.visibleStrokes().size)
            assertEquals(s1.id, loadedEarlier.visibleStrokes().first().id)

            // Verify history lists checkpoints for this document
            val history = store.listHistory(infDoc.id)
            assertTrue(history.size >= 3) // create + cp1 + cp2
            assertTrue(history.any { it.id == cp1.id && it.isNamedSnapshot })

            // Verify bare repo has no `.git/index` and passes full integrity check
            assertTrue(File(dir, "repo.git/HEAD").exists())
            assertTrue(!File(dir, "repo.git/index").exists())
            assertTrue(store.verifyIntegrity())
            assertEquals(fixedDoc.id, store.loadDocument(fixedDoc.id).id)

            // Verify canonical `git fsck --full --strict` & GitHub preview artifacts in tree
            val fsck = ProcessBuilder("git", "--git-dir", File(dir, "repo.git").absolutePath, "fsck", "--full", "--strict")
                .redirectErrorStream(true)
                .start()
            val fsckOut = fsck.inputStream.bufferedReader().readText()
            assertEquals("git fsck failed:\n$fsckOut", 0, fsck.waitFor())

            val lsTree = ProcessBuilder("git", "--git-dir", File(dir, "repo.git").absolutePath, "ls-tree", "-r", "--name-only", "HEAD")
                .redirectErrorStream(true)
                .start()
            val treeFiles = lsTree.inputStream.bufferedReader().readText().lines().toSet()
            assertEquals(0, lsTree.waitFor())
            assertTrue("Missing root README.md", "README.md" in treeFiles)
            assertTrue("Missing doc README.md", "docs/${infDoc.id}/README.md" in treeFiles)
            assertTrue("Missing doc preview.svg", "docs/${infDoc.id}/preview.svg" in treeFiles)
        }
    }

    @Test
    fun `uncommitted WAL survives simulated crash and recovers cleanly on reopen`() = runTest {
        val dir = tempFolder.newFolder("ws_crash")
        val docId: String
        val strokeId = Ulid.next()

        // Session 1: flush to atomic WAL and close abruptly WITHOUT calling checkpoint()
        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            val doc = store.createDocument("Crash Test", CanvasMode.Infinite)
            docId = doc.id
            val layerId = doc.primaryStrokeLayer().id
            val stroke = Stroke(
                id = strokeId,
                brush = BrushSpec.defaultFor(ToolType.PENCIL),
                points = listOf(StrokePoint(10f, 20f), StrokePoint(300f, 400f)),
            )
            val (_, delta) = DocumentCommand.AddStroke(layerId, stroke).apply(doc)
            store.flushDelta(docId, delta)
        }

        // Session 2: reopen workspace -> WAL must auto-replay and commit safely
        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            val recovered = store.loadDocument(docId)
            assertEquals(1, recovered.visibleStrokes().size)
            assertEquals(strokeId, recovered.visibleStrokes().first().id)
            assertTrue(store.verifyIntegrity())
        }
    }

    @Test
    fun `variations isolate alternate sketch branches cleanly`() = runTest {
        val dir = tempFolder.newFolder("ws_var")
        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            val doc = store.createDocument("Logo Exploration", CanvasMode.Fixed(2048, 2048, 300))
            val layerId = doc.primaryStrokeLayer().id

            store.createVariation("color-study", switchToIt = true)
            val sBranch = Stroke(
                id = Ulid.next(),
                brush = BrushSpec.defaultFor(ToolType.MARKER),
                points = listOf(StrokePoint(100f, 100f), StrokePoint(400f, 400f)),
            )
            val (branchDoc, _) = DocumentCommand.AddStroke(layerId, sBranch).apply(doc)
            store.checkpoint(branchDoc, label = "Bold color study")
            assertEquals(1, store.loadDocument(doc.id).visibleStrokes().size)

            // Switch back to main -> original remains untouched
            store.switchVariation("main")
            assertEquals(0, store.loadDocument(doc.id).visibleStrokes().size)
            assertTrue(store.verifyIntegrity())
        }
    }

    @Test
    fun `concurrent checkpoints and WAL flushes never corrupt the repository`() = runTest {
        val dir = tempFolder.newFolder("ws_concurrent")
        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            var doc = store.createDocument("Stress Test", CanvasMode.Infinite)
            val layerId = doc.primaryStrokeLayer().id

            val jobs = (1..20).map { i ->
                async {
                    val stroke = Stroke(
                        id = Ulid.next(),
                        brush = BrushSpec.defaultFor(ToolType.PEN),
                        points = listOf(StrokePoint(i * 5f, 0f), StrokePoint(i * 5f, 50f)),
                    )
                    val (next, delta) = DocumentCommand.AddStroke(layerId, stroke).apply(doc)
                    store.flushDelta(doc.id, delta)
                    store.checkpoint(next, label = "Step $i")
                }
            }
            jobs.awaitAll()
            assertTrue(store.verifyIntegrity())
        }
    }

    @Test
    fun `undo and redo stack survives close and reopen cleanly`() = runTest {
        val dir = tempFolder.newFolder("ws_undo_persist")
        val docId: String

        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            var doc = store.createDocument("", CanvasMode.Infinite)
            assertEquals("Unnamed Sketch", doc.title)
            docId = doc.id

            val layerId = doc.primaryStrokeLayer().id
            val stack = space.owork.draw.engine.CommandStack()
            val s1 = Stroke(brush = BrushSpec.defaultFor(ToolType.PEN), points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)))
            val s2 = Stroke(brush = BrushSpec.defaultFor(ToolType.PEN), points = listOf(StrokePoint(20f, 20f), StrokePoint(30f, 30f)))

            doc = stack.execute(doc, DocumentCommand.AddStroke(layerId, s1)).first
            doc = stack.execute(doc, DocumentCommand.AddStroke(layerId, s2)).first
            // Undo s2 so undo=[s1], redo=[s2]
            doc = stack.undo(doc)!!.first
            store.checkpoint(doc, label = "Half undone")
        }

        // Reopen in a brand-new store & CommandStack -> both undo and redo must still work
        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            var reopened = store.loadDocument(docId)
            assertEquals(1, reopened.visibleStrokes().size)

            val restoredStack = space.owork.draw.engine.CommandStack()
            restoredStack.restore(reopened.undoStack)
            assertTrue(restoredStack.canUndo)
            assertTrue(restoredStack.canRedo)

            // Redo brings back s2
            reopened = restoredStack.redo(reopened)!!.first
            assertEquals(2, reopened.visibleStrokes().size)

            // Undo twice clears canvas
            reopened = restoredStack.undo(reopened)!!.first
            reopened = restoredStack.undo(reopened)!!.first
            assertEquals(0, reopened.visibleStrokes().size)
        }
    }

    @Test
    fun `two store instances on same workspace serialize without OverlappingFileLockException`() = runTest {
        val dir = tempFolder.newFolder("ws_two_instances")
        GitWorkspaceStore(dir).use { storeA ->
            GitWorkspaceStore(dir).use { storeB ->
                storeA.initialize()
                storeB.initialize()
                val doc = storeA.createDocument("Shared", CanvasMode.Infinite)
                val layerId = doc.primaryStrokeLayer().id

                val jobs = (1..12).map { i ->
                    async {
                        val target = if (i % 2 == 0) storeA else storeB
                        val stroke = Stroke(brush = BrushSpec.defaultFor(ToolType.PEN), points = listOf(StrokePoint(i.toFloat(), 0f)))
                        val (_, delta) = DocumentCommand.AddStroke(layerId, stroke).apply(doc)
                        target.flushDeltas(doc.id, listOf(delta))
                        target.listDocuments()
                    }
                }
                jobs.awaitAll()
                assertTrue(storeA.verifyIntegrity())
            }
        }
    }

    @Test
    fun `partial erase replacement survives WAL recovery, checkpoint, and undo after reopen`() = runTest {
        val dir = tempFolder.newFolder("ws_partial_erase")
        val docId: String
        val orig = Stroke(
            id = Ulid.next(),
            brush = BrushSpec.defaultFor(ToolType.LINE),
            points = listOf(StrokePoint(0f, 0f), StrokePoint(200f, 0f)),
        )
        val fragments = PartialEraser.eraseCapsule(orig, 100f, 0f, 100f, 0f, eraserRadius = 15f)!!
        assertEquals(2, fragments.size)

        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            var doc = store.createDocument("Partial Erase Persistence", CanvasMode.Infinite)
            docId = doc.id
            val layerId = doc.primaryStrokeLayer().id
            val stack = CommandStack()

            val (d1, delta1) = stack.execute(doc, DocumentCommand.AddStroke(layerId, orig))
            doc = d1
            store.flushDelta(docId, delta1)

            val (_, delta2) = stack.execute(doc, DocumentCommand.ReplaceStrokes(layerId, listOf(orig.id), fragments))
            store.flushDelta(docId, delta2)
        }

        // Reopen without checkpoint -> WAL recovers partial erase and undo stack
        GitWorkspaceStore(dir).use { store ->
            store.initialize()
            var reopened = store.loadDocument(docId)
            assertEquals(fragments.map { it.id }.toSet(), reopened.visibleStrokes().map { it.id }.toSet())

            val stack = CommandStack().apply { restore(reopened.undoStack) }
            reopened = stack.undo(reopened)!!.first
            assertEquals(listOf(orig.id), reopened.visibleStrokes().map { it.id })

            reopened = stack.redo(reopened)!!.first
            assertEquals(fragments.map { it.id }.toSet(), reopened.visibleStrokes().map { it.id }.toSet())
            assertTrue(store.verifyIntegrity())
        }
    }
}
