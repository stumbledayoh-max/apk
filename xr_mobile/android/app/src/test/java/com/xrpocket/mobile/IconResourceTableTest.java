package com.xrpocket.mobile;

import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.value.Entry;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class IconResourceTableTest {
    private static TableBlock roundTrip(TableBlock table) throws IOException {
        table.refreshFull();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        table.writeBytes(output);
        assertTrue("resource table must not serialize empty", output.size() > 0);
        return TableBlock.load(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test public void createsWritableTableForMinimalApk() throws Exception {
        TableBlock table = TableBlock.createEmpty();
        int id = IconResourceTable.add(table, "com.example.game");
        assertEquals(0x7f, id >>> 24);
        TableBlock restored = roundTrip(table);
        assertEquals(1, restored.size());
        assertEquals("com.example.game", restored.get(0).getName());
        Entry icon = restored.get(0).getEntry("drawable", IconResourceTable.NAME);
        assertNotNull(icon);
        assertEquals(id, icon.getResourceId());
        assertEquals(IconResourceTable.PATH, icon.getResValue().getValueAsString());
    }

    @Test public void preservesResourceNamespaceDifferentFromApplicationId() throws Exception {
        TableBlock table = new TableBlock();
        PackageBlock pack = table.newPackage(0x7f, "com.example.namespace");
        Entry existing = pack.getOrCreate("", "string", "original");
        existing.setValueAsString("keep me");
        int originalId = existing.getResourceId();
        int iconId = IconResourceTable.add(table, "com.example.game");
        TableBlock restored = roundTrip(table);
        assertEquals("com.example.namespace", restored.get(0).getName());
        assertEquals(originalId, restored.get(0).getEntry("string", "original").getResourceId());
        assertEquals("keep me", restored.get(0).getEntry("string", "original").getResValue().getValueAsString());
        assertEquals(iconId, restored.get(0).getEntry("drawable", IconResourceTable.NAME).getResourceId());
    }

    @Test public void rejectsAmbiguousOrDuplicateIconResource() throws Exception {
        TableBlock ambiguous = new TableBlock();
        ambiguous.newPackage(0x7f, "com.example.one");
        ambiguous.newPackage(0x02, "com.example.two");
        try { IconResourceTable.add(ambiguous, "com.example.game"); fail(); }
        catch (IOException expected) { }
        TableBlock table = TableBlock.createEmpty();
        IconResourceTable.add(table, "com.example.game");
        try { IconResourceTable.add(table, "com.example.game"); fail(); }
        catch (IOException expected) { }
    }
}
