package net.talaatharb.stttts.generation.stableaudio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import ai.onnx.proto.OnnxMl;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

class BFloat16ModelTest {
    @Test
    void promotesWeightsConstantsCastsAndTypeMetadata() throws Exception {
        OnnxMl.TensorProto tensor = OnnxMl.TensorProto.newBuilder().setName("weight").setDataType(16)
                .addDims(2).setRawData(ByteString.copyFrom(new byte[] {(byte) 0x80, 0x3f, 0, (byte) 0xc0})).build();
        OnnxMl.GraphProto graph = OnnxMl.GraphProto.newBuilder().addInitializer(tensor)
                .addNode(OnnxMl.NodeProto.newBuilder().setOpType("Cast").addAttribute(
                        OnnxMl.AttributeProto.newBuilder().setName("to").setI(16)))
                .addNode(OnnxMl.NodeProto.newBuilder().setOpType("Constant").addAttribute(
                        OnnxMl.AttributeProto.newBuilder().setName("value").setT(tensor)))
                .addOutput(OnnxMl.ValueInfoProto.newBuilder().setName("out").setType(
                        OnnxMl.TypeProto.newBuilder().setTensorType(
                                OnnxMl.TypeProto.Tensor.newBuilder().setElemType(16)))).build();
        OnnxMl.GraphProto converted = BFloat16Model.promote(graph);
        assertEquals(1, converted.getInitializer(0).getDataType());
        ByteBuffer values = converted.getInitializer(0).getRawData().asReadOnlyByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1f, values.getFloat());
        assertEquals(-2f, values.getFloat());
        assertEquals(1, converted.getNode(0).getAttribute(0).getI());
        assertEquals(1, converted.getNode(1).getAttribute(0).getT().getDataType());
        assertEquals(1, converted.getOutput(0).getType().getTensorType().getElemType());
    }

    @Test
    void refusesCustomTensorRtOperators() {
        OnnxMl.GraphProto graph = OnnxMl.GraphProto.newBuilder().addNode(
                OnnxMl.NodeProto.newBuilder().setDomain("samel").setOpType("diff_attn_swa")).build();
        assertThrows(IOException.class, () -> BFloat16Model.promote(graph));
    }
}
