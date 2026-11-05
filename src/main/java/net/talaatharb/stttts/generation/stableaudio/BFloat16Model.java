package net.talaatharb.stttts.generation.stableaudio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import ai.onnx.proto.OnnxMl;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import net.talaatharb.stttts.model.ProgressListener;

/** Expands BF16 weights exactly and promotes BF16 operations for ONNX Runtime's CPU/DirectML providers. */
final class BFloat16Model {
    private static final int BF16 = 16;
    private static final int FLOAT = 1;

    private BFloat16Model() {
    }

    static void prepare(Path source, Path target, ProgressListener progress) throws IOException {
        if (Files.isRegularFile(target)) {
            return;
        }
        progress.checkCancelled();
        progress.update("Preparing portable Stable Audio decoder (one-time BF16 to FP32 conversion)", -1);
        OnnxMl.ModelProto model;
        try (InputStream in = Files.newInputStream(source)) {
            CodedInputStream coded = CodedInputStream.newInstance(in);
            coded.setSizeLimit(512 * 1024 * 1024);
            model = OnnxMl.ModelProto.parseFrom(coded);
        }
        OnnxMl.ModelProto converted = model.toBuilder().setGraph(promote(model.getGraph())).build();
        progress.checkCancelled();
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            try (OutputStream out = Files.newOutputStream(part)) {
                converted.writeTo(out);
            }
            progress.checkCancelled();
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    static OnnxMl.GraphProto promote(OnnxMl.GraphProto graph) throws IOException {
        OnnxMl.GraphProto.Builder result = graph.toBuilder();
        for (int i = 0; i < graph.getInitializerCount(); i++) {
            result.setInitializer(i, promote(graph.getInitializer(i)));
        }
        for (int i = 0; i < graph.getNodeCount(); i++) {
            OnnxMl.NodeProto node = graph.getNode(i);
            if (!node.getDomain().isEmpty() && !node.getDomain().equals("ai.onnx")) {
                throw new IOException("Stable Audio decoder needs an unsupported custom operator: "
                        + node.getDomain() + "::" + node.getOpType());
            }
            OnnxMl.NodeProto.Builder n = node.toBuilder();
            for (int a = 0; a < node.getAttributeCount(); a++) {
                OnnxMl.AttributeProto attribute = node.getAttribute(a);
                OnnxMl.AttributeProto.Builder attr = attribute.toBuilder();
                if (node.getOpType().equals("Cast") && attribute.getName().equals("to")
                        && attribute.getI() == BF16) {
                    attr.setI(FLOAT);
                }
                if (attribute.hasT()) {
                    attr.setT(promote(attribute.getT()));
                }
                if (attribute.hasG()) {
                    attr.setG(promote(attribute.getG()));
                }
                for (int t = 0; t < attribute.getTensorsCount(); t++) {
                    attr.setTensors(t, promote(attribute.getTensors(t)));
                }
                for (int g = 0; g < attribute.getGraphsCount(); g++) {
                    attr.setGraphs(g, promote(attribute.getGraphs(g)));
                }
                n.setAttribute(a, attr);
            }
            result.setNode(i, n);
        }
        for (int i = 0; i < graph.getInputCount(); i++) {
            result.setInput(i, promote(graph.getInput(i)));
        }
        for (int i = 0; i < graph.getOutputCount(); i++) {
            result.setOutput(i, promote(graph.getOutput(i)));
        }
        for (int i = 0; i < graph.getValueInfoCount(); i++) {
            result.setValueInfo(i, promote(graph.getValueInfo(i)));
        }
        return result.build();
    }

    private static OnnxMl.ValueInfoProto promote(OnnxMl.ValueInfoProto value) {
        if (value.getType().hasTensorType() && value.getType().getTensorType().getElemType() == BF16) {
            return value.toBuilder().setType(value.getType().toBuilder()
                    .setTensorType(value.getType().getTensorType().toBuilder().setElemType(FLOAT))).build();
        }
        return value;
    }

    private static OnnxMl.TensorProto promote(OnnxMl.TensorProto tensor) throws IOException {
        if (tensor.getDataType() != BF16) {
            return tensor;
        }
        if (tensor.getExternalDataCount() > 0) {
            throw new IOException("External BF16 tensors are not supported by this decoder conversion");
        }
        ByteBuffer raw;
        if (tensor.hasRawData()) {
            ByteBuffer source = tensor.getRawData().asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            if (source.remaining() % 2 != 0) {
                throw new IOException("Invalid BF16 tensor data length");
            }
            raw = ByteBuffer.allocate(source.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
            while (source.hasRemaining()) {
                raw.putInt((source.getShort() & 0xffff) << 16);
            }
        } else {
            raw = ByteBuffer.allocate(tensor.getInt32DataCount() * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int bits : tensor.getInt32DataList()) {
                raw.putInt((bits & 0xffff) << 16);
            }
        }
        return tensor.toBuilder().setDataType(FLOAT).clearInt32Data().clearFloatData()
                .setRawData(ByteString.copyFrom(raw.array())).build();
    }
}
