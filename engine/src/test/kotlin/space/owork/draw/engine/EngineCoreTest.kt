package space.owork.draw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class EngineCoreTest {

    @Test
    fun `ulids are strictly monotonic within same millisecond`() {
        val ids = (1..200).map { Ulid.next(1_760_000_000_000L) }
        assertEquals(200, ids.toSet().size)
        for (i in 1 until ids.size) {
            assertTrue("Expected ${ids[i - 1]} < ${ids[i]}", ids[i - 1] < ids[i])
        }
    }

    @Test
    fun `viewport camera screenToWorld and worldToScreen roundtrip with zoom and rotation`() {
        val cam = ViewportCamera(offsetX = 412.5f, offsetY = 288.25f, zoom = 2.4f, rotationRad = (PI / 6.0).toFloat())
        val original = WorldPoint(123.4f, -567.8f)
        val screen = cam.worldToScreen(original.x, original.y)
        val back = cam.screenToWorld(screen.x, screen.y)
        assertEquals(original.x, back.x, 1e-3f)
        assertEquals(original.y, back.y, 1e-3f)
    }

    @Test
    fun `command stack supports add, erase, undo, and redo with incremental deltas`() {
        var doc = Document.createNew("Test Sketch", CanvasMode.Infinite, nowMs = 1000L)
        val layerId = doc.primaryStrokeLayer().id
        val stack = CommandStack()

        val s1 = Stroke(
            id = Ulid.next(1001L),
            brush = BrushSpec.defaultFor(ToolType.PEN),
            points = listOf(StrokePoint(0f, 0f), StrokePoint(100f, 100f)),
        )
        val (d1, delta1) = stack.execute(doc, DocumentCommand.AddStroke(layerId, s1), nowMs = 1001L)
        doc = d1
        assertEquals(1, doc.visibleStrokes().size)
        assertEquals(listOf(s1), delta1.addedStrokes)

        val (d2, delta2) = stack.execute(doc, DocumentCommand.EraseStrokes(layerId, listOf(s1.id)), nowMs = 1002L)
        doc = d2
        assertEquals(0, doc.visibleStrokes().size)
        assertEquals(listOf(s1.id), delta2.addedTombstones)

        val (d3, _) = stack.undo(doc, nowMs = 1003L)!!
        doc = d3
        assertEquals(1, doc.visibleStrokes().size)

        val (d4, _) = stack.undo(doc, nowMs = 1004L)!!
        doc = d4
        assertEquals(0, doc.visibleStrokes().size)
        assertFalse(stack.canUndo)
        assertTrue(stack.canRedo)

        val (d5, _) = stack.redo(doc, nowMs = 1005L)!!
        doc = d5
        assertEquals(1, doc.visibleStrokes().size)
    }

    @Test
    fun `stroke chunks are deterministic and roll over cleanly`() {
        val strokes = (1..520).map { i ->
            Stroke(
                id = Ulid.next(2000L + i),
                brush = BrushSpec.defaultFor(ToolType.PENCIL),
                points = listOf(StrokePoint(i.toFloat(), i * 0.5f, pressure = 0.65f)),
            )
        }
        val chunksA = DocumentCodec.encodeStrokeChunks(strokes)
        val chunksB = DocumentCodec.encodeStrokeChunks(strokes.reversed())
        assertEquals(listOf("c_0001.jsonl", "c_0002.jsonl"), chunksA.keys.toList())
        assertTrue(chunksA["c_0001.jsonl"]!!.contentEquals(chunksB["c_0001.jsonl"]!!))
        assertTrue(chunksA["c_0002.jsonl"]!!.contentEquals(chunksB["c_0002.jsonl"]!!))

        val decoded = DocumentCodec.decodeStrokeChunks(chunksA)
        assertEquals(520, decoded.size)
        assertEquals(strokes.first().id, decoded.first().id)
        assertEquals(strokes.last().id, decoded.last().id)
    }

    @Test
    fun `semantic 3-way merge unions strokes and respects tombstones without conflicts`() {
        val sBase = Stroke(id = Ulid.next(3000L), brush = BrushSpec.defaultFor(ToolType.PEN), points = listOf(StrokePoint(0f, 0f)))
        val sOurs = Stroke(id = Ulid.next(3001L), brush = BrushSpec.defaultFor(ToolType.PEN), points = listOf(StrokePoint(10f, 10f)))
        val sTheirs = Stroke(id = Ulid.next(3002L), brush = BrushSpec.defaultFor(ToolType.MARKER), points = listOf(StrokePoint(20f, 20f)))

        val base = Layer.StrokeLayer(id = "L1", strokes = listOf(sBase))
        val ours = base.copy(strokes = listOf(sBase, sOurs), tombstones = setOf(sBase.id))
        val theirs = base.copy(strokes = listOf(sBase, sTheirs))

        val merged = LayerMerger.mergeStrokeLayer(base, ours, theirs)
        assertEquals(setOf(sOurs.id, sTheirs.id), merged.activeStrokes.map { it.id }.toSet())
        assertTrue(sBase.id in merged.tombstones)
    }

    @Test
    fun `preview generator produces valid svg and github markdown`() {
        var doc = Document.createNew("Concept Sheet", CanvasPresets.defaultPresets.first().toCanvasMode(), nowMs = 5000L)
        val layerId = doc.primaryStrokeLayer().id
        val stroke = Stroke(
            id = Ulid.next(5001L),
            brush = BrushSpec.defaultFor(ToolType.PEN, 0xFF2F66D0.toInt()),
            points = listOf(StrokePoint(10f, 10f), StrokePoint(50f, 30f), StrokePoint(90f, 20f)),
        )
        doc = DocumentCommand.AddStroke(layerId, stroke).apply(doc, 5001L).first

        val svg = DocumentPreviewGenerator.generateSvg(doc).decodeToString()
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains("clip-path=\"url(#artboard)\""))
        assertTrue(svg.contains("#2F66D0"))

        val readme = DocumentPreviewGenerator.generateDocumentReadme(doc).decodeToString()
        assertTrue(readme.contains("![Concept Sheet](./preview.svg)"))
        assertTrue(readme.contains("2560 × 1600 px"))

        val rootMd = DocumentPreviewGenerator.generateWorkspaceReadme(listOf(doc.meta)).decodeToString()
        assertNotEquals("", rootMd)
        assertTrue(rootMd.contains("./docs/${doc.id}/README.md"))
        assertTrue(rootMd.contains("./docs/${doc.id}/preview.svg"))
    }

    @Test
    fun `stroke smoother trims endpoint lift hook and rounds sharp corner`() {
        val raw = listOf(
            StrokePoint(0f, 0f, pressure = 0.2f),
            StrokePoint(50f, 0f, pressure = 0.6f),
            StrokePoint(50f, 50f, pressure = 0.7f),
            StrokePoint(49f, 48.5f, pressure = 0.1f), // tiny liftoff reversal hook
        )
        val refined = StrokeSmoother.refine(raw, StrokeSmoothingLevel.MEDIUM, roundTurnsAndEnds = true)
        assertTrue(refined.size > raw.size)
        // Liftoff hook trimmed -> endpoint stays near (50, 50)
        assertEquals(50f, refined.last().x, 1e-2f)
        assertEquals(50f, refined.last().y, 1e-2f)
        // Sharp corner at (50, 0) was rounded inward
        assertTrue(refined.none { it.x == 50f && it.y == 0f })
    }
}
