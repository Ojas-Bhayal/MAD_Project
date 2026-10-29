#!/usr/bin/env python3
"""
inspect_tflite_graph.py - Examines the actual FlatBuffer graph of a TFLite model.

Extracts:
- input tensor shapes and dtypes
- output tensor shapes and dtypes
- output class count

Fails closed with exit code 1 if the model is malformed or graph cannot be inspected.
"""

import argparse
import json
import os
import struct
import sys

TENSOR_TYPE_MAP = {
    0: 'float32',
    1: 'float16',
    2: 'int32',
    3: 'uint8',
    4: 'int64',
    5: 'string',
    6: 'bool',
    7: 'int16',
    8: 'complex64',
    9: 'int8'
}


def inspect_model_graph(model_path: str):
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Model file not found: {model_path}")

    with open(model_path, 'rb') as f:
        buf = f.read()

    if len(buf) < 16:
        raise ValueError(f"Model buffer too small: {len(buf)} bytes")

    if buf[4:8] != b'TFL3':
        raise ValueError(f"Invalid TFLite flatbuffer magic identifier: {buf[4:8]}")

    root_offset = struct.unpack('<I', buf[:4])[0]
    vtable_offset = struct.unpack('<i', buf[root_offset:root_offset+4])[0]
    vtable_start = root_offset - vtable_offset

    # Model table: Field 2 is subgraphs (vtable offset 8)
    subgraphs_field_offset = struct.unpack('<H', buf[vtable_start+8:vtable_start+10])[0]
    if subgraphs_field_offset == 0:
        raise ValueError("Model contains no subgraphs field")

    subgraphs_vec_pos = root_offset + subgraphs_field_offset + struct.unpack('<I', buf[root_offset+subgraphs_field_offset:root_offset+subgraphs_field_offset+4])[0]
    num_subgraphs = struct.unpack('<I', buf[subgraphs_vec_pos:subgraphs_vec_pos+4])[0]
    if num_subgraphs == 0:
        raise ValueError("Model contains 0 subgraphs")

    sg_offset = subgraphs_vec_pos + 4 + struct.unpack('<I', buf[subgraphs_vec_pos+4:subgraphs_vec_pos+8])[0]
    sg_vtable_offset = struct.unpack('<i', buf[sg_offset:sg_offset+4])[0]
    sg_vtable = sg_offset - sg_vtable_offset

    # SubGraph:
    # Field 0: tensors (vtable offset 4)
    # Field 1: inputs (vtable offset 6)
    # Field 2: outputs (vtable offset 8)
    tensors_field_off = struct.unpack('<H', buf[sg_vtable+4:sg_vtable+6])[0]
    tensors_vec_pos = sg_offset + tensors_field_off + struct.unpack('<I', buf[sg_offset+tensors_field_off:sg_offset+tensors_field_off+4])[0]

    inputs_field_off = struct.unpack('<H', buf[sg_vtable+6:sg_vtable+8])[0]
    inputs_vec_pos = sg_offset + inputs_field_off + struct.unpack('<I', buf[sg_offset+inputs_field_off:sg_offset+inputs_field_off+4])[0]
    num_inputs = struct.unpack('<I', buf[inputs_vec_pos:inputs_vec_pos+4])[0]
    if num_inputs == 0:
        raise ValueError("SubGraph has 0 inputs")
    input_indices = [struct.unpack('<I', buf[inputs_vec_pos+4+i*4:inputs_vec_pos+8+i*4])[0] for i in range(num_inputs)]

    outputs_field_off = struct.unpack('<H', buf[sg_vtable+8:sg_vtable+10])[0]
    outputs_vec_pos = sg_offset + outputs_field_off + struct.unpack('<I', buf[sg_offset+outputs_field_off:sg_offset+outputs_field_off+4])[0]
    num_outputs = struct.unpack('<I', buf[outputs_vec_pos:outputs_vec_pos+4])[0]
    if num_outputs == 0:
        raise ValueError("SubGraph has 0 outputs")
    output_indices = [struct.unpack('<I', buf[outputs_vec_pos+4+i*4:outputs_vec_pos+8+i*4])[0] for i in range(num_outputs)]

    def parse_tensor(idx):
        t_offset = tensors_vec_pos + 4 + idx * 4 + struct.unpack('<I', buf[tensors_vec_pos+4+idx*4:tensors_vec_pos+8+idx*4])[0]
        t_vtable_off = struct.unpack('<i', buf[t_offset:t_offset+4])[0]
        t_vtable = t_offset - t_vtable_off
        t_vtable_size = struct.unpack('<H', buf[t_vtable:t_vtable+2])[0]

        shape_off = struct.unpack('<H', buf[t_vtable+4:t_vtable+6])[0] if t_vtable_size > 4 else 0
        type_off = struct.unpack('<H', buf[t_vtable+6:t_vtable+8])[0] if t_vtable_size > 6 else 0

        shape = []
        if shape_off > 0:
            shape_vec_pos = t_offset + shape_off + struct.unpack('<I', buf[t_offset+shape_off:t_offset+shape_off+4])[0]
            num_dims = struct.unpack('<I', buf[shape_vec_pos:shape_vec_pos+4])[0]
            shape = [struct.unpack('<i', buf[shape_vec_pos+4+d*4:shape_vec_pos+8+d*4])[0] for d in range(num_dims)]

        dtype = 'float32' # default in FlatBuffers if field is omitted
        if type_off > 0:
            raw_type = struct.unpack('<b', buf[t_offset+type_off:t_offset+type_off+1])[0]
            dtype = TENSOR_TYPE_MAP.get(raw_type, f'raw_{raw_type}')

        return {'shape': shape, 'dtype': dtype}

    in_tensor = parse_tensor(input_indices[0])
    out_tensor = parse_tensor(output_indices[0])

    out_classes = out_tensor['shape'][-1] if len(out_tensor['shape']) > 0 else 0

    return {
        'input_shape': in_tensor['shape'],
        'input_dtype': in_tensor['dtype'],
        'output_shape': out_tensor['shape'],
        'output_dtype': out_tensor['dtype'],
        'output_class_count': out_classes
    }


def main():
    parser = argparse.ArgumentParser(description="Inspect TFLite FlatBuffer graph")
    parser.add_argument("--model", required=True, help="Path to .tflite model file")
    args = parser.parse_args()

    try:
        graph = inspect_model_graph(args.model)
        print(json.dumps(graph, indent=2))
    except Exception as e:
        sys.stderr.write(f"Error inspecting TFLite model graph: {e}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
