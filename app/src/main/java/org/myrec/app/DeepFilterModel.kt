package org.myrec.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/* Adapt only the verified, pinned split graphs. Weights and operators stay unchanged;
 * hidden states become explicit and only the newest frame advances each GRU. */
object DeepFilterModel {
    private data class Field(val tag: Int,val wire: Int,val bytes: ByteArray)
    private fun varint(value: Long): ByteArray {
        var n=value;val out=ByteArrayOutputStream()
        while (n and -128L!=0L) { out.write((n.toInt() and 127) or 128);n=n ushr 7 }
        out.write(n.toInt());return out.toByteArray()
    }
    private fun fields(data: ByteArray): MutableList<Field> {
        var at=0
        fun number(): Long {
            var result=0L
            for (shift in 0..63 step 7) {
                require(at<data.size);val b=data[at++].toInt() and 255
                require(shift!=63 || b<=1)
                result=result or ((b and 127).toLong() shl shift)
                if (b<128) return result
            }
            error("Invalid model integer")
        }
        val result=mutableListOf<Field>()
        while (at<data.size) {
            val key=number();val wire=(key and 7).toInt();val start=at
            val value=when (wire) {
                0 -> { number();data.copyOfRange(start,at) }
                1,5 -> { val n=if (wire==1) 8 else 4;require(n<=data.size-at);data.copyOfRange(at,at+n).also { at+=n } }
                2 -> { val n=number();require(n in 0..(data.size-at).toLong());data.copyOfRange(at,at+n.toInt()).also { at+=n.toInt() } }
                else -> error("Invalid model field")
            }
            require(key ushr 3 in 1..536870911);result.add(Field((key ushr 3).toInt(),wire,value))
        }
        return result
    }
    private fun bytes(fields: List<Field>)=ByteArrayOutputStream().apply {
        for (f in fields) { write(varint((f.tag.toLong() shl 3)+f.wire));if (f.wire==2) write(varint(f.bytes.size.toLong()));write(f.bytes) }
    }.toByteArray()
    private fun message(tag: Int,vararg parts: Field)=Field(tag,2,bytes(parts.toList()))
    private fun text(tag: Int,value: String)=Field(tag,2,value.toByteArray())
    private fun integer(tag: Int,value: Long)=Field(tag,0,varint(value))
    private fun name(parts: List<Field>,tag: Int)=parts.first { it.tag==tag }.bytes.toString(Charsets.UTF_8)
    private fun valueInfo(tag: Int,name: String,shape: List<Int>)=message(tag,text(1,name),
        message(2,message(1,integer(1,1),message(2,*shape.map { message(1,integer(1,it.toLong())) }.toTypedArray()))))
    private fun shape(field: Field,axis: Int,frames: Int): Field {
        val value=fields(field.bytes);val type=fields(value.first { it.tag==2 }.bytes)
        val tensor=fields(type.first { it.tag==1 }.bytes);val shape=tensor.first { it.tag==2 }
        val dimensions=fields(shape.bytes);dimensions[axis]=message(1,integer(1,frames.toLong()))
        tensor[tensor.indexOf(shape)]=shape.copy(bytes=bytes(dimensions))
        type[type.indexOfFirst { it.tag==1 }]=message(1,*tensor.toTypedArray())
        value[value.indexOfFirst { it.tag==2 }]=message(2,*type.toTypedArray())
        return field.copy(bytes=bytes(value))
    }
    fun adapt(data: ByteArray,kind: String): ByteArray {
        require(data.size in 1..(16*1024*1024) && kind in listOf("enc","erb_dec","df_dec"))
        val model=fields(data);val graphField=model.single { it.tag==7 };val graph=fields(graphField.bytes)
        val result=mutableListOf<Field>();val extra=mutableListOf<Field>();var states=0;var slices=0
        fun slice(source: String,axis: Int): List<Field> {
            val inputs=listOf(-1L,Int.MAX_VALUE.toLong(),axis.toLong()).mapIndexed { i,n ->
                val name="myrec_slice_${slices}_$i"
                extra.add(message(5,integer(1,1),integer(2,7),text(8,name),Field(9,2,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array())))
                text(1,name)
            }
            slices++
            return listOf(message(1,text(1,source+"_all"),*inputs.toTypedArray(),text(2,source),text(4,"Slice")))
        }
        for (f in graph) {
            if (f.tag==11 || f.tag==12) {
                val name=name(fields(f.bytes),1);val axis=if (name in listOf("emb","lsnr","coefs","235")) 1 else 2
                result.add(shape(f,axis,if (kind=="enc" && f.tag==11 || name=="c0") 10 else 1));continue
            }
            if (f.tag!=1) { result.add(f);continue }
            val node=fields(f.bytes);val op=name(node,4);val label=node.firstOrNull { it.tag==3 }?.bytes?.toString(Charsets.UTF_8)
            if (op=="GRU") {
                val state=states++;val inputs=node.withIndex().filter { it.value.tag==1 }
                require(inputs.size==6);node[inputs[5].index]=text(1,"state_in$state")
                val hidden=node.filter { it.tag==2 }[1].bytes.toString(Charsets.UTF_8)
                extra.add(valueInfo(11,"state_in$state",listOf(1,1,256)))
                extra.add(valueInfo(12,"state_out$state",listOf(1,1,256)))
                extra.add(message(1,text(1,hidden),text(2,"state_out$state"),text(4,"Identity")))
            }
            val cut=kind=="enc" && label in listOf("/erb_conv0/3/Relu","/df_conv1/Relu") || kind=="df_dec" && node.any { it.tag==2 && it.bytes.toString(Charsets.UTF_8)=="coefs" }
            var source=""
            if (cut) { val index=node.indexOfFirst { it.tag==2 };source=name(node,2);node[index]=text(2,source+"_all") }
            result.add(f.copy(bytes=bytes(node)))
            if (cut) result.addAll(slice(source,if (kind=="enc") 2 else 1))
        }
        require(states==if (kind=="enc") 1 else 2)
        require(slices==when (kind) { "enc" -> 2;"df_dec" -> 1;else -> 0 })
        result.addAll(extra);model[model.indexOf(graphField)]=graphField.copy(bytes=bytes(result))
        return bytes(model)
    }
    fun prepare(directory: File) {
        for (kind in listOf("enc","erb_dec","df_dec")) {
            val expected=adapt(File(directory,"$kind.onnx").readBytes(),kind)
            val target=File(directory,"$kind-stream.onnx")
            if (target.isFile && target.readBytes().contentEquals(expected)) continue
            val partial=File(directory,".$kind-${uid()}.partial")
            try {
                FileOutputStream(partial).use { it.write(expected);it.fd.sync() }
                check(partial.renameTo(target)) { "Cannot prepare streaming noise reduction" }
            } finally { partial.delete() }
        }
    }
}
