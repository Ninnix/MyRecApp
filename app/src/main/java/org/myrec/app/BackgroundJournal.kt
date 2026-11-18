package org.myrec.app

import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlin.math.*

/** Independent checked records leave a readable prefix if recording is interrupted. */
object BackgroundJournal {
    private const val magic=0x4d524d31
    private const val limit=4*1024*1024
    data class Sample(val track: String,val signature: String,val raw: BackgroundMatte,val final: FloatArray,val inputSignature: String="",val reset: Boolean=false,
        val frame: Int=-1,val captureTime: Double=raw.time)
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun dimensions(w: Int,h: Int)=w in 1..512 && h in 1..512
    fun canonical(values: FloatArray)=FloatArray(values.size) { i ->
        require(values[i].isFinite())
        (values[i].coerceIn(0f,1f)*65535).roundToInt()/65535f
    }
    class Writer(val path: File) : Closeable {
        private val file=RandomAccessFile(path,"rw")
        private data class Stamp(val time: Double,val signature: String,val width: Int,val height: Int,val guide: FloatArray)
        private val last=mutableMapOf<String,Stamp>()
        private var finished=false
        private val frames=mutableListOf<Int>()
        var count=0;private set
        init {
            try { require(file.length()==0L);file.writeInt(magic);file.fd.sync() }
            catch (error: Throwable) { file.close();throw error }
        }
        private fun block(bytes: ByteArray) {
            require(bytes.size in 1..limit)
            val start=file.filePointer
            try { file.writeInt(bytes.size);file.write(digest(bytes));file.write(bytes) }
            catch (error: Throwable) { file.setLength(start);file.seek(start);throw error }
        }
        fun append(sample: Sample) {
            check(!finished && count<500000)
            val m=sample.raw;val n=m.width*m.height
            require(sample.track.length in 1..64 && sample.signature.length in 1..128 && dimensions(m.width,m.height) && dimensions(m.guideWidth,m.guideHeight))
            val previous=last[sample.track]
            require(previous!=null || last.size<1000)
            require(m.time.isFinite() && m.time>=0 && m.time>(previous?.time ?: -1.0))
            require(m.alpha.size==n+3 && sample.final.size==n+3 && m.guide.size==m.guideWidth*m.guideHeight)
            require(m.person==null || m.person.size==192*192)
            require(sample.frame in -1..2999999)
            val bytes=ByteArrayOutputStream()
            val guide=BackgroundTemporal.probe(m)
            val reset=sample.reset || previous==null || previous.signature!=sample.signature || m.time-previous.time>.5 ||
                m.width!=previous.width || m.height!=previous.height || BackgroundTemporal.cut(guide,previous.guide)
            DataOutputStream(bytes).use { out ->
                require(sample.inputSignature.length<=128)
                out.writeByte(1);out.writeUTF(sample.track);out.writeUTF(sample.signature);out.writeDouble(m.time);out.writeUTF(sample.inputSignature);out.writeBoolean(reset);out.writeInt(sample.frame)
                out.writeInt(m.width);out.writeInt(m.height);out.writeInt(m.guideWidth);out.writeInt(m.guideHeight);out.writeBoolean(m.person!=null)
                DeflaterOutputStream(out).use { compressed ->
                    val packed=ByteArray(65536);var used=0
                    for (values in listOf(m.alpha,sample.final,m.guide)+listOfNotNull(m.person)) for (v in values) {
                        require(v.isFinite() && v in 0f..1f)
                        val value=(v*65535).roundToInt();packed[used++]=(value ushr 8).toByte();packed[used++]=value.toByte()
                        if (used==packed.size) { compressed.write(packed);used=0 }
                    }
                    if (used>0) compressed.write(packed,0,used)
                }
            }
            block(bytes.toByteArray());frames.add(sample.frame);last[sample.track]=Stamp(m.time,sample.signature,m.width,m.height,guide);count++
            if (count%10==0) file.fd.sync()
        }
        fun finish(sourceHash: String,sourceTimes: DoubleArray?=null) {
            check(!finished);require(sourceHash.matches(Regex("[a-f0-9]{64}")))
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeByte(2);out.writeInt(count);out.writeUTF(sourceHash);out.writeInt(if (sourceTimes==null) 0 else count)
                if (sourceTimes!=null) for (frame in frames) { require(frame in sourceTimes.indices);out.writeDouble(sourceTimes[frame]) }
            }
            block(bytes.toByteArray());file.fd.sync();finished=true
        }
        override fun close() { try { file.fd.sync() } finally { file.close() } }
    }
    class Reader(path: File) : Closeable {
        private val file=RandomAccessFile(path,"r")
        private data class Entry(var time: Double,val captureTime: Double,val offset: Long,val size: Int,val reset: Boolean,val frame: Int)
        private val tracks=mutableMapOf<String,MutableList<Entry>>()
        private val entries=mutableListOf<Entry>()
        private val cache=linkedMapOf<Long,Sample>()
        var sourceHash: String?=null;private set
        var incomplete=false;private set
        init {
            try {
                require(file.readInt()==magic) { "Unknown recorded mask format" }
                var count=0
                while (file.filePointer<file.length()) {
                    val offset=file.filePointer
                    if (file.length()-offset<36) { incomplete=true;break }
                    val size=file.readInt();require(size in 1..limit) { "Invalid recorded mask size" }
                    val checksum=ByteArray(32).also { file.readFully(it) }
                    if (size>file.length()-file.filePointer) { incomplete=true;break }
                    val start=file.filePointer
                    when (file.readUnsignedByte()) {
                        1 -> {
                            val track=file.readUTF();val signature=file.readUTF();val time=file.readDouble()
                            require(file.readUTF().length<=128);val reset=file.readBoolean();val frame=file.readInt();require(frame in -1..2999999)
                            require(track.length in 1..64 && signature.length in 1..128 && time.isFinite() && time>=0)
                            val items=tracks.getOrPut(track) { require(tracks.size<1000);mutableListOf() }
                            require(items.isEmpty() || items.last().time<time)
                            require(++count<=500000 && file.filePointer-start<=size)
                            val entry=Entry(time,time,offset,size,reset,frame);items.add(entry);entries.add(entry)
                        }
                        2 -> {
                            file.seek(start);val data=ByteArray(size).also { file.readFully(it) }
                            require(digest(data).contentEquals(checksum)) { "Recorded mask footer is damaged" }
                            DataInputStream(ByteArrayInputStream(data)).use { input ->
                                input.readByte();require(input.readInt()==count);sourceHash=input.readUTF()
                                require(sourceHash!!.matches(Regex("[a-f0-9]{64}")))
                                val clocks=input.readInt();require(clocks==0 || clocks==count)
                                if (clocks!=0) for (entry in entries) { entry.time=input.readDouble();require(entry.time.isFinite() && entry.time>=0) }
                                require(input.read()==-1)
                            }
                            require(file.filePointer==file.length()) { "Data after recorded mask footer" }
                        }
                        else -> error("Unknown recorded mask record")
                    }
                    file.seek(start+size)
                }
                if (sourceHash==null) incomplete=true
                validateTimes()
            } catch (error: Throwable) { file.close();throw error }
        }
        private fun validateTimes() {
            for (items in tracks.values) require((1 until items.size).all { items[it].time>items[it-1].time }) { "Invalid recorded mask clock" }
        }
        fun recoverClock(sourceTimes: DoubleArray) {
            check(sourceHash==null)
            for (entry in entries) if (entry.frame>=0 && entry.frame<sourceTimes.size) entry.time=sourceTimes[entry.frame]
            for (items in tracks.values) items.removeAll { it.frame>=sourceTimes.size && it.frame>=0 }
            validateTimes();cache.clear()
        }
        fun times(track: String)=tracks[track].orEmpty().map { it.time }
        fun preceding(track: String,time: Double,count: Int,origin: Double): List<Double> {
            require(count in 0..8 && time.isFinite() && origin.isFinite())
            val entries=tracks[track] ?: return emptyList()
            var lo=0;var hi=entries.size
            while (lo<hi) { val mid=(lo+hi)/2;if (entries[mid].time<=time+.000001) lo=mid+1 else hi=mid }
            var at=lo-1
            if (at>=0 && abs(entries[at].time-time)<.000001) { if (entries[at].reset) return emptyList();at-- }
            val result=mutableListOf<Double>()
            while (at>=0 && result.size<count && entries[at].time>=origin-.000001) {
                result.add(entries[at].time);if (entries[at].reset) break;at--
            }
            return result
        }
        fun at(track: String,time: Double): Sample? {
            require(time.isFinite() && time>=0)
            val entries=tracks[track] ?: return null
            var lo=0;var hi=entries.size
            while (lo<hi) { val mid=(lo+hi)/2;if (entries[mid].time<=time+.000001) lo=mid+1 else hi=mid }
            if (lo==0) return null
            val entry=entries[lo-1]
            cache[entry.offset]?.let { return it }
            file.seek(entry.offset);require(file.readInt()==entry.size)
            val checksum=ByteArray(32).also { file.readFully(it) };val bytes=ByteArray(entry.size).also { file.readFully(it) }
            require(digest(bytes).contentEquals(checksum)) { "Recorded mask is damaged" }
            val sample=DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readByte().toInt()==1);val id=input.readUTF();val signature=input.readUTF();val at=input.readDouble();val prefix=input.readUTF()
                val reset=input.readBoolean();val frame=input.readInt()
                require(prefix.length<=128)
                val w=input.readInt();val h=input.readInt();val gw=input.readInt();val gh=input.readInt();val person=input.readBoolean()
                require(id==track && at==entry.captureTime && frame==entry.frame && dimensions(w,h) && dimensions(gw,gh))
                InflaterInputStream(input).use { data ->
                    val packed=ByteArray(((w*h+3)*2+gw*gh+if (person) 192*192 else 0)*2)
                    var read=0
                    while (read<packed.size) {
                        val n=data.read(packed,read,packed.size-read);require(n>0) { "Truncated recorded mask" };read+=n
                    }
                    require(data.read()==-1) { "Recorded mask has excess samples" }
                    var offset=0
                    fun values(n: Int)=FloatArray(n) {
                        val high=packed[offset++].toInt() and 255;val low=packed[offset++].toInt() and 255
                        ((high shl 8) or low)/65535f
                    }
                    val raw=values(w*h+3);val final=values(w*h+3);val guide=values(gw*gh);val mask=if (person) values(192*192) else null
                    Sample(id,signature,BackgroundMatte(w,h,raw,guide,entry.time,gw,gh,mask),final,prefix,reset,frame,at)
                }
            }
            if (cache.size>=2) cache.remove(cache.keys.first())
            cache[entry.offset]=sample;return sample
        }
        override fun close() { file.close();cache.clear() }
    }
}
