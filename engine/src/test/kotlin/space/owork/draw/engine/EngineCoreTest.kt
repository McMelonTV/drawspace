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

    @Test
    fun `bucket fill fills enclosed square and rejects open infinite background`() {
        var doc = Document.createNew("Fill Test", CanvasMode.Infinite, nowMs = 6000L)
        val layerId = doc.primaryStrokeLayer().id
        val pen = BrushSpec.defaultFor(ToolType.PEN, 0xFF181A1F.toInt())
        val square = Stroke(
            brush = pen,
            points = listOf(
                StrokePoint(0f, 0f),
                StrokePoint(200f, 0f),
                StrokePoint(200f, 200f),
                StrokePoint(0f, 200f),
                StrokePoint(0f, 0f),
            ),
        )
        doc = DocumentCommand.AddStroke(layerId, square).apply(doc, 6001L).first

        // Inside closed square -> succeeds
        val fillBrush = BrushSpec.defaultFor(ToolType.FILL, 0xFF2F66D0.toInt())
        val insideRes = BucketFillEngine.floodFill(doc, 100f, 100f, fillBrush)
        assertTrue("Expected Success, got $insideRes", insideRes is BucketFillResult.Success)
        val fillStroke = (insideRes as BucketFillResult.Success).stroke
        assertEquals(ToolType.FILL, fillStroke.brush.tool)
        assertTrue(fillStroke.hitTest(100f, 100f, 2f))
        assertFalse(fillStroke.hitTest(250f, 250f, 2f))

        // Outside square on infinite canvas -> returns OpenRegion (caller fills infinite canvas bg)
        val outsideRes = BucketFillEngine.floodFill(doc, 250f, 250f, fillBrush)
        assertTrue("Expected OpenRegion, got $outsideRes", outsideRes is BucketFillResult.OpenRegion)
    }

    @Test
    fun `bucket fill stays watertight on 3-line diagonal triangle at 150 percent zoom`() {
        var doc = Document.createNew("Triangle Fill", CanvasMode.Infinite, nowMs = 7000L)
        val layerId = doc.primaryStrokeLayer().id
        val lineBrush = BrushSpec.defaultFor(ToolType.LINE, 0xFF181A1F.toInt()) // 5.5px
        val pA = StrokePoint(-480f, 0f)
        val pB = StrokePoint(624f, -288f)
        val pC = StrokePoint(336f, 240f)
        for ((s, e) in listOf(pA to pB, pB to pC, pC to pA)) {
            doc = DocumentCommand.AddStroke(layerId, Stroke(brush = lineBrush, points = listOf(s, e))).apply(doc).first
        }
        val visibleViewport = WorldRect(-850f, -530f, 850f, 530f) // ~1700 world px (150% zoom on 2560px)
        val fillBrush = BrushSpec.defaultFor(ToolType.FILL, 0xFF2F66D0.toInt())
        val res = BucketFillEngine.floodFill(doc, 150f, 0f, fillBrush, visibleWorldBounds = visibleViewport)
        assertTrue("Expected Success inside diagonal triangle, got $res", res is BucketFillResult.Success)
        val filled = (res as BucketFillResult.Success).stroke
        assertTrue(filled.hitTest(150f, 0f, 1f))
        assertFalse(filled.hitTest(-600f, -300f, 1f))
    }

    @Test
    fun `bucket fill reaches exact 60-degree isometric corner with zero gap even beside adjacent fills`() {
        var doc = Document.createNew("Iso Corner", CanvasMode.Infinite, nowMs = 8000L)
        val layerId = doc.primaryStrokeLayer().id
        val whiteLine = BrushSpec.defaultFor(ToolType.LINE, 0xFFF7F7F5.toInt()) // 5.5px
        // Isometric rhombus: top=(144, -249.42), right=(192, -221.70), bottom=(144, -193.99), left=(96, -221.70)
        val top = StrokePoint(144f, -249.42f)
        val right = StrokePoint(192f, -221.70f)
        val bottom = StrokePoint(144f, -193.99f)
        val left = StrokePoint(96f, -221.70f)
        for ((s, e) in listOf(top to right, right to bottom, bottom to left, left to top)) {
            doc = DocumentCommand.AddStroke(layerId, Stroke(brush = whiteLine, points = listOf(s, e))).apply(doc).first
        }
        // Add an existing overlapping adjacent fill right next to the top corner to prove it cannot carve holes
        val adjacentFill = Stroke(
            brush = BrushSpec.defaultFor(ToolType.FILL, 0xFFD93848.toInt()),
            points = listOf(StrokePoint(120f, -270f), StrokePoint(165f, -246f)),
        )
        doc = DocumentCommand.AddStroke(layerId, adjacentFill).apply(doc).first

        val viewport = WorldRect(40f, -300f, 240f, -150f)
        val yellow = BrushSpec.defaultFor(ToolType.FILL, 0xFFE3A72F.toInt())
        val res = BucketFillEngine.floodFill(doc, 144f, -221.7f, yellow, visibleWorldBounds = viewport)
        assertTrue("Expected Success, got $res", res is BucketFillResult.Success)
        val fillStroke = (res as BucketFillResult.Success).stroke
        // Even 1px inside the sharp 60° top corner (deep under the 5.5px white lines!) must be solidly filled
        assertTrue("Corner at (144, -248.4) must be solidly filled", fillStroke.hitTest(144f, -248.4f, 0f))
        assertTrue("Center must be filled", fillStroke.hitTest(144f, -221.7f, 0f))
        assertFalse("Outside rhombus must not bleed", fillStroke.hitTest(144f, -253f, 0f))
    }

    @Test
    fun `grid snapper hits exact square and isometric 3-way intersections`() {
        val squareBg = CanvasBackground(pattern = BackgroundPattern.SQUARE_GRID, spacingPx = 40f)
        val sq = GridSnapper.snapWorld(102f, -58f, squareBg)
        assertEquals(120f, sq.x, 1e-4f)
        assertEquals(-40f, sq.y, 1e-4f)

        val isoBg = CanvasBackground(pattern = BackgroundPattern.ISOMETRIC, spacingPx = 48f)
        val iso = GridSnapper.snapWorld(75f, 115f, isoBg)
        val s = 48f
        // All 3 isometric shader dot products must be exact integer multiples of spacing s
        val k1 = iso.x / s
        val k2 = (0.5f * iso.x + 0.8660254f * iso.y) / s
        val k3 = (-0.5f * iso.x + 0.8660254f * iso.y) / s
        assertEquals(kotlin.math.round(k1), k1, 1e-3f)
        assertEquals(kotlin.math.round(k2), k2, 1e-3f)
        assertEquals(kotlin.math.round(k3), k3, 1e-3f)
    }
}
