package org.myrec.app

object RevisionCacheTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        test("revision cache owns immutable copies on both insertion and retrieval") {
            val source = Project(clips = mutableListOf(Clip(path = "fixture.mp4",sourceOut = 2.0,
                effects = mutableListOf(Effect.create("Noir")))))
            val expected = source.duplicate(); val cache = RevisionCache(100,2)
            cache.put("a",source,20); source.clips.clear()
            check(cache["a"] == expected)
            cache["a"]!!.clips.single().effects.single().setNumber("crush",.3f)
            check(cache["a"] == expected && cache.footprint() == (1 to 20))
        }
        test("revision cache bounds serialized weight and entry count with recent-use eviction") {
            val cache = RevisionCache(10,3); val project = Project()
            cache.put("a",project,5); cache.put("b",project,5)
            check(cache["a"] != null); cache.put("c",project,5)
            check(cache["b"] == null && cache["a"] != null && cache["c"] != null && cache.footprint() == (2 to 10))
            cache.put("a",project,11)
            check(cache["a"] == null && cache.footprint() == (1 to 5))
            val small = RevisionCache(100,2)
            for (key in listOf("a","b","c")) small.put(key,project,1)
            check(small["a"] == null && small.footprint() == (2 to 2))
            val disabled = RevisionCache(0,0); disabled.put("a",project,1)
            check(disabled["a"] == null && disabled.footprint() == (0 to 0))
        }
    }
}
