package org.myrec.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Collections share tables; offsets are absolute file offsets, not face offsets. */
object FontFile {
    data class Face(val index: Int,val name: String,val postScript: String,val weights: List<Int>,val weight: Int,val italic: Boolean)
    fun faces(input: ByteBuffer): List<Face> {
        val data=input.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
        fun checkRange(at: Long,count: Long) { require(at>=0 && count>=0 && at<=data.limit().toLong()-count) { "Invalid font table bounds" } }
        fun u16(at: Int): Int { checkRange(at.toLong(),2);return data.getShort(at).toInt() and 65535 }
        fun u32(at: Int): Long { checkRange(at.toLong(),4);return data.getInt(at).toLong() and 0xffffffffL }
        fun offset(at: Int): Int { val n=u32(at);require(n<=Int.MAX_VALUE);return n.toInt() }
        fun tag(at: Int): String { checkRange(at.toLong(),4);return (0..3).map { data.get(at+it).toInt().toChar() }.joinToString("") }
        val offsets=if (tag(0)=="ttcf") {
            require(u32(4) in listOf(0x10000L,0x20000L)) { "Unsupported font collection" }
            val count=u32(8);require(count in 1..256);checkRange(12,count*4)
            List(count.toInt()) { offset(12+it*4) }.also { require(it.distinct().size==it.size) }
        } else listOf(0)
        return offsets.mapIndexed { index,at ->
            require(u32(at) in listOf(0x10000L,0x4f54544fL,0x74727565L)) { "Choose a TrueType or OpenType font" }
            val count=u16(at+4);require(count in 1..256);checkRange(at+12L,count*16L)
            val tables=linkedMapOf<String,Pair<Int,Int>>()
            repeat(count) { i ->
                val entry=at+12+i*16;val name=tag(entry);val start=offset(entry+8);val size=offset(entry+12)
                checkRange(start.toLong(),size.toLong());require(name !in tables);tables[name]=start to size
            }
            for (required in listOf("head","maxp","cmap","name")) require(required in tables) { "Incomplete font" }
            require("glyf" in tables && "loca" in tables || "CFF " in tables || "CFF2" in tables) { "Missing font outlines" }
            val (names,length)=tables.getValue("name");require(length>=6)
            val format=u16(names);require(format in 0..1)
            val n=u16(names+2);val strings=u16(names+4);require(6L+n*12<=length && strings<=length)
            fun name(id: Int): String? {
                val choices=mutableListOf<Pair<Int,String>>()
                repeat(n) { j ->
                    val p=names+6+j*12;val platform=u16(p);val encoding=u16(p+2);val language=u16(p+4)
                    if (u16(p+6)==id) {
                        val size=u16(p+8);val start=strings.toLong()+u16(p+10)
                        require(start+size<=length);if (size in 1..4096) {
                            val charset=when { platform in listOf(0,3) -> Charsets.UTF_16BE
                                platform==1 && encoding==0 -> runCatching { Charset.forName("x-MacRoman") }.getOrDefault(Charsets.ISO_8859_1)
                                else -> null }
                            if (charset!=null) {
                                if (platform in listOf(0,3)) require(size%2==0)
                                val bytes=ByteArray(size);val view=data.duplicate();view.position(names+start.toInt());view.get(bytes)
                                val text=String(bytes,charset).trim().filter { !it.isISOControl() }
                                if (text.isNotBlank()) choices.add((if (platform==3 && language==0x409) 3 else if (platform==0) 2 else 1) to text)
                            }
                        }
                    }
                }
                return choices.maxByOrNull { it.first }?.second
            }
            val ps=name(6)?.take(140) ?: throw IllegalArgumentException("The font has no PostScript name")
            val display=(name(4) ?: name(16) ?: name(1) ?: ps).take(200)
            val os=tables["OS/2"];val head=tables.getValue("head");require(head.second>=54)
            var weight=if (os!=null && os.second>=6) u16(os.first+4).coerceIn(100,1000) else 400
            val italic=if (os!=null && os.second>=64) u16(os.first+62) and 1!=0 else u16(head.first+44) and 2!=0
            var weights=emptyList<Int>()
            tables["fvar"]?.let { (start,size) ->
                require(size>=16);val axes=u16(start+4);val number=u16(start+8);val stride=u16(start+10)
                require(number<=64 && stride>=20 && axes.toLong()+number*stride<=size)
                repeat(number) { j ->
                    val p=start+axes+j*stride
                    if (tag(p)=="wght") {
                        fun fixed(at: Int)=data.getInt(at)/65536.0
                        val low=fixed(p+4);val normal=fixed(p+8);val high=fixed(p+12)
                        require(low<=normal && normal<=high)
                        val a=maxOf(100,low.toInt());val b=minOf(1000,high.toInt())
                        if (a<=b) { weight=normal.toInt().coerceIn(a,b);weights=(listOf(a,b,weight)+(100..1000 step 100).filter { it in a..b }).distinct().sorted() }
                    }
                }
            }
            Face(index,display,ps,weights,weight,italic)
        }
    }
}
