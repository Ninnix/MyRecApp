#!/usr/bin/env python3
"""Repack the pinned PPHumanSeg FP32 graph for the C GPU runtime; no learned tensor changes."""
from pathlib import Path
from collections import Counter,deque
import onnx,numpy as np,struct,hashlib,json
from onnx import numpy_helper,helper
project=Path(__file__).resolve().parent.parent
root=project/'app/src/main/assets/models'
spec=json.loads((project/'data/models.json').read_text())['person']
source=root/'person.onnx'
assert hashlib.sha256(source.read_bytes()).hexdigest()==spec['sha256']
model=onnx.shape_inference.infer_shapes(onnx.load(source))
values={t.name:numpy_helper.to_array(t) for t in model.graph.initializer}
shapes={v.name:[d.dim_value for d in v.type.tensor_type.shape.dim] for v in [*model.graph.input,*model.graph.value_info,*model.graph.output]}
nodes=list(model.graph.node);tail=nodes[-3:]
assert [n.op_type for n in tail]==['Transpose','Softmax','Transpose']
assert all(list(helper.get_attribute_value(n.attribute[0]))==[0,3,2,1] for n in [tail[0],tail[2]])
assert tail[1].input[0]==tail[0].output[0] and tail[2].input[0]==tail[1].output[0]
assert helper.get_attribute_value(tail[1].attribute[0])==-1
ops=[('Input','input',[],['x'],'',None)]
for index,node in enumerate(nodes[:-3]):
 attrs={a.name:helper.get_attribute_value(a) for a in node.attribute};inputs=list(node.input);outputs=list(node.output);weights=None
 if node.op_type=='Conv':
  weights=[values[inputs[1]],values[inputs[2]]];w,b=weights
  assert attrs.get('group',1)==1 and attrs.get('dilations',[1,1])==[1,1]
  assert w.dtype==np.float32 and b.dtype==np.float32
  pad=-233 if attrs.get('auto_pad')==b'SAME_UPPER' else 0
  assert pad==-233 or attrs.get('pads')==[0,0,0,0]
  sy,sx=attrs.get('strides',[1,1]);cout,cin,kh,kw=w.shape
  params=f'0={cout} 1={kw} 11={kh} 3={sx} 13={sy} 4={pad} 5=1 6={w.size}'
  typ='Convolution';inputs=inputs[:1]
 elif node.op_type=='Relu':typ='ReLU';params='0=0'
 elif node.op_type=='Add':typ='BinaryOp';params='0=0'
 elif node.op_type=='Resize':
  assert attrs['mode']==b'linear' and attrs['coordinate_transformation_mode']==b'half_pixel'
  h,w=shapes[outputs[0]][2:];typ='Interp';params=f'0=2 3={h} 4={w} 6=0';inputs=inputs[:1]
 elif node.op_type=='Concat':
  assert attrs['axis']==1;typ='Concat';params='0=0'
 else:raise ValueError(node.op_type)
 ops.append((typ,f'op{index}',inputs,outputs,params,weights))
ops.append(('Softmax','probabilities',[tail[0].input[0]],[tail[2].output[0]],'0=0 1=1',None))
uses=Counter(v for op in ops for v in op[2]);aliases={};lines=[];blobs=0;weight_arrays=[]
with (root/'person-preview.bin').open('wb') as binary:
 for typ,name,inputs,outputs,params,weights in ops:
  inputs=[aliases[v].popleft() if v in aliases else v for v in inputs]
  lines.append(' '.join([typ,name,str(len(inputs)),str(len(outputs)),*inputs,*outputs,params]).rstrip());blobs+=len(outputs)
  if weights:
   binary.write(struct.pack('<I',0))
   for array in weights:binary.write(array.astype('<f4').tobytes());weight_arrays.append(hashlib.sha256(array.tobytes()).hexdigest())
  for output in outputs:
   if uses[output]>1:
    names=[f'{output}_branch{i}' for i in range(uses[output])];aliases[output]=deque(names)
    lines.append(' '.join(['Split',f'split{len(lines)}','1',str(len(names)),output,*names]));blobs+=len(names)
(root/'person-preview.param').write_text(f'7767517\n{len(lines)} {blobs}\n'+'\n'.join(lines)+'\n')
print(len(lines),'layers',blobs,'blobs',(root/'person-preview.bin').stat().st_size,'weight bytes',len(weight_arrays),'unchanged float32 tensors')
