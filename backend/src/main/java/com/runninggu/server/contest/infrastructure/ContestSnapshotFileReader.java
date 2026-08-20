package com.runninggu.server.contest.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.contest.application.snapshot.ContestSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ContestSnapshotFileReader {

    private final ObjectMapper objectMapper;

    public ContestSnapshotFileReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ContestSnapshot read(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            rejectBom(bytes);
            String json = decodeUtf8(bytes);
            return objectMapper.readValue(json, ContestSnapshot.class);
        } catch (IOException exception) {
            throw new ContestSnapshotReadException("대회 snapshot을 읽을 수 없습니다: " + path, exception);
        }
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private void rejectBom(byte[] bytes) {
        if (bytes.length >= 3
                && Byte.toUnsignedInt(bytes[0]) == 0xEF
                && Byte.toUnsignedInt(bytes[1]) == 0xBB
                && Byte.toUnsignedInt(bytes[2]) == 0xBF) {
            throw new ContestSnapshotReadException("대회 snapshot은 UTF-8 BOM을 포함할 수 없습니다");
        }
    }
}
