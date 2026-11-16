package net.rseb.ls4tc421.model

import ls4tc421.protocol.ProgramName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramFileTest {
    private fun ok(text: String) = (ProgramFile.decode(text) as? ImportResult.Ok)?.spec
        ?: error("refused: ${ProgramFile.decode(text)}")

    private fun refused(text: String) = (ProgramFile.decode(text) as? ImportResult.Refused)?.error
        ?: error("accepted: $text")

    @Test fun roundTripKeepsPointsAndOptions() {
        val spec = Aquarium.RISE_ONLY.withLink(4, ChannelLink(source = 3, percent = 25))
        val back = ok(ProgramFile.encode(spec))
        assertEquals(spec, back)
        assertEquals(spec.levels(), back.levels())
    }

    @Test fun roundTripOfAnUnusualName() {
        val spec = ProgramSpec("B \"n\"\\1").withPoint(2, 0, 7) // 7 characters: quotes, backslash, space
        assertEquals(spec, ok(ProgramFile.encode(spec)))
    }

    /** D23: a name saved before the rule is exported as is; the file is then refused at import. */
    @Test fun aNameThatCannotBeSentIsExportedAsIsAndRefusedAtImport() {
        val old = Aquarium.RISE_ONLY.renamed("court_basique")
        val text = ProgramFile.encode(old)
        assertEquals("court_basique", (Json.parse(text) as Map<*, *>)["name"])
        assertEquals(ImportError.InvalidName(ProgramName.Problem.TooLong(13)), refused(text))
    }

    @Test fun importRefusesAnAccentedOrTooLongNameWithoutCorrectingIt() {
        val good = ProgramFile.encode(Aquarium.RISE_ONLY)
        fun named(name: String) = good.replaceFirst("\"name\": \"aqua\"", "\"name\": ${Json.quote(name)}")
        assertEquals(ImportError.InvalidName(ProgramName.Problem.Refused("é")), refused(named("été")))
        assertEquals(ImportError.InvalidName(ProgramName.Problem.TooLong(10)), refused(named("aquarium_1")))
        assertEquals(ImportError.InvalidName(ProgramName.Problem.Refused("\t")), refused(named("a\tb")))
        // A conforming name imports as before.
        assertEquals(ImportError.InvalidName(ProgramName.Problem.TooLong(8)), refused(named("tropical")))
        assertEquals(Aquarium.RISE_ONLY.renamed("aquari7"), ok(named("aquari7")))
        assertEquals(Aquarium.RISE_ONLY, ok(good))
    }

    @Test fun exportContainsTheComputed48Steps() {
        val text = ProgramFile.encode(Aquarium.RISE_ONLY)
        val root = Json.parse(text) as Map<*, *>
        assertEquals("ls4tc421-program", root["format"])
        assertEquals(1L, root["version"])
        assertEquals(mapOf("axis" to "15:30"), root["symmetry"])
        val steps = root["steps"] as List<*>
        assertEquals(48, steps.size)
        val levels = steps.map { ((it as Map<*, *>)["levels"] as List<*>).map { v -> (v as Long).toInt() } }
        assertEquals(Aquarium.STEPS, levels)
        assertEquals("08:30", (steps[17] as Map<*, *>)["time"])
    }

    @Test fun exportDropsThePointsThatSymmetryIgnores() {
        val withGhost = Aquarium.RISE_ONLY.withPoint(0, 35, 3)
        assertEquals(Aquarium.RISE_ONLY, ok(ProgramFile.encode(withGhost)))
    }

    @Test fun fileWithOnlyStepsIsImportedAs48KeyPoints() {
        val steps = Aquarium.STEPS.joinToString(",\n") { "[${it.joinToString(",")}]" }
        val spec = ok("""{"format":"ls4tc421-program","version":1,"name":"steps","steps":[$steps]}""")
        assertEquals("steps", spec.name)
        assertTrue(spec.points.all { it.size == 48 })
        assertEquals(Aquarium.STEPS, spec.levels())
        assertEquals(null, spec.symmetryAxis)
    }

    @Test fun stepsAsObjectsAreAcceptedToo() {
        val full = ProgramFile.encode(ProgramSpec.fromSteps("x", Aquarium.STEPS))
        val root = Json.parse(full) as Map<*, *>
        val stepsOnly = full.substring(full.indexOf("\"steps\""))
        val spec = ok("""{"format":"ls4tc421-program","version":1,"name":"x",$stepsOnly""")
        assertEquals(Aquarium.STEPS, spec.levels())
        assertTrue(root.containsKey("channels"))
    }

    @Test fun invalidFilesAreRefusedWithTheReason() {
        assertTrue(refused("not json") is ImportError.NotJson)
        assertTrue(refused("""{"format":"other","version":1}""") is ImportError.NotAProgram)
        assertTrue(refused("""[1,2]""") is ImportError.NotAProgram)
        assertEquals(ImportError.UnsupportedVersion("2"), refused("""{"format":"ls4tc421-program","version":2,"name":"x"}"""))
        assertEquals("name", (refused("""{"format":"ls4tc421-program","version":1,"name":"","steps":[]}""") as ImportError.Invalid).where)
        assertEquals("steps", (refused("""{"format":"ls4tc421-program","version":1,"name":"x","steps":[[0,0,0,0,0]]}""") as ImportError.Invalid).where)
        assertTrue(refused("x".repeat(ProgramFile.MAX_BYTES + 1)) is ImportError.TooLarge)
    }

    @Test fun aBadValueDeepInsideRefusesTheWholeFile() {
        val good = ProgramFile.encode(Aquarium.RISE_ONLY)
        val badLevel = good.replaceFirst("\"level\": 20", "\"level\": 120")
        assertEquals("channels[0].points[2].level", (refused(badLevel) as ImportError.Invalid).where)
        val badTime = good.replaceFirst("\"time\": \"08:30\"", "\"time\": \"08:15\"")
        assertTrue((refused(badTime) as ImportError.Invalid).where.endsWith(".time"))
        val badAxis = good.replace("\"axis\": \"15:30\"", "\"axis\": \"15:10\"")
        assertEquals("symmetry.axis", (refused(badAxis) as ImportError.Invalid).where)
        val chain = good
            .replaceFirst("\"follows\": null", "\"follows\": { \"channel\": 2, \"percent\": 50 }")
            .replaceFirst("\"follows\": null", "\"follows\": { \"channel\": 3, \"percent\": 50 }")
        assertEquals("channels", (refused(chain) as ImportError.Invalid).where)
        val twice = good.replace("\"channel\": 2,", "\"channel\": 1,")
        assertTrue((refused(twice) as ImportError.Invalid).detail.contains("twice"))
        val fractional = good.replaceFirst("\"level\": 20", "\"level\": 20.5")
        assertTrue(refused(fractional) is ImportError.Invalid)
    }

    @Test fun jsonReaderHandlesEscapesAndRefusesDuplicates() {
        assertEquals(mapOf("a" to "é\n\"x\""), Json.parse("""{"a":"é\n\"x\""}"""))
        assertTrue(refused("""{"format":"ls4tc421-program","format":"ls4tc421-program"}""") is ImportError.NotJson)
        assertEquals(listOf(1L, -2L, 1.5, true, null), Json.parse("[1,-2,1.5,true,null]"))
        // A byte order mark left by an editor is tolerated.
        assertEquals(Aquarium.RISE_ONLY, ok("\uFEFF" + ProgramFile.encode(Aquarium.RISE_ONLY)))
    }

    @Test fun suggestedFileName() {
        assertEquals("aqua.json", ProgramFile.fileName(ProgramSpec("aqua")))
        assertEquals("a_b_c.json", ProgramFile.fileName(ProgramSpec("a/b:c")))
    }

    @Test fun storedFormsRoundTrip() {
        val spec = Aquarium.RISE_ONLY.withLink(1, ChannelLink(0, 90))
        assertEquals(spec.points, StoredForm.parsePoints(StoredForm.points(spec.points)))
        assertEquals(spec.links, StoredForm.parseLinks(StoredForm.links(spec.links)))
        assertEquals(spec.levels(), StoredForm.parseLevels(StoredForm.levels(spec.levels())))
        assertEquals(List(5) { emptyList<KeyPoint>() }, StoredForm.parsePoints("||||"))
        assertEquals(null, StoredForm.parseLevels("garbage"))
    }
}
