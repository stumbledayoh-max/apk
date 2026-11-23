package com.xrpocket.mobile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Reads class descriptors without executing the game's DEX. Bounds are checked before access. */
final class DexClasses {
    private static final int MAX_ITEMS = 2_000_000;
    static Set<String> read(byte[] bytes) throws IOException {
        if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x'
                || bytes[3] != '\n' || bytes[7] != 0) throw new IOException("DEX inválido ou formato não suportado");
        for (int i = 4; i <= 6; i++) if (bytes[i] < '0' || bytes[i] > '9') throw new IOException("Versão DEX inválida");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (u32(buffer, 32) != bytes.length || u32(buffer, 36) != 112 || u32(buffer, 40) != 0x12345678L)
            throw new IOException("Cabeçalho DEX inválido");
        long stringCount = u32(buffer, 56), stringOff = u32(buffer, 60);
        long typeCount = u32(buffer, 64), typeOff = u32(buffer, 68);
        long classCount = u32(buffer, 96), classOff = u32(buffer, 100);
        table(bytes.length, stringOff, stringCount, 4);
        table(bytes.length, typeOff, typeCount, 4);
        table(bytes.length, classOff, classCount, 32);
        Set<String> classes = new HashSet<>();
        for (int i = 0; i < classCount; i++) {
            long typeIndex = u32(buffer, (int) classOff + i * 32);
            if (typeIndex >= typeCount) throw new IOException("Tipo DEX fora da tabela");
            long stringIndex = u32(buffer, (int) typeOff + (int) typeIndex * 4);
            if (stringIndex >= stringCount) throw new IOException("String DEX fora da tabela");
            long rawOffset = u32(buffer, (int) stringOff + (int) stringIndex * 4);
            if (rawOffset >= bytes.length) throw new IOException("Nome DEX truncado");
            int pos = (int) rawOffset;
            boolean terminated = false;
            for (int n = 0; n < 5 && pos < bytes.length; n++) {
                int b = bytes[pos++] & 255;
                if ((b & 128) == 0) { terminated = true; break; }
            }
            if (!terminated) throw new IOException("Comprimento DEX inválido");
            int end = pos;
            while (end < bytes.length && end - pos <= 65535 && bytes[end] != 0) end++;
            if (end >= bytes.length || end - pos > 65535 || end - pos < 3
                    || bytes[pos] != 'L' || bytes[end - 1] != ';') throw new IOException("Descritor DEX inválido");
            // A one-to-one byte mapping also preserves DEX's modified UTF-8 names.
            String descriptor = new String(bytes, pos, end - pos, StandardCharsets.ISO_8859_1);
            if (!classes.add(descriptor)) throw new IOException("Classe duplicada dentro do DEX");
        }
        return classes;
    }

    private static long u32(ByteBuffer buffer, int offset) { return Integer.toUnsignedLong(buffer.getInt(offset)); }
    private static void table(int length, long offset, long count, int width) throws IOException {
        if (count > MAX_ITEMS || offset > length || count * width > length - offset)
            throw new IOException("Tabela DEX truncada ou grande demais");
    }
    private DexClasses() { }
}
