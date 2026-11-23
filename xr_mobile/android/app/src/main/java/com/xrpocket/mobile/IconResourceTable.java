package com.xrpocket.mobile;

import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.value.Entry;

import java.io.IOException;
import java.util.Iterator;

/** Adds an icon resource without changing existing package IDs or resources. */
final class IconResourceTable {
    static final String NAME = "bigxr_custom_icon";
    static final String PATH = "res/drawable-nodpi/bigxr_custom_icon.png";

    static int add(TableBlock table, String packageName) throws IOException {
        // Minimal APKs can have an empty table. Resource package names may also
        // differ from applicationId when an app was built with a namespace.
        PackageBlock pack = null;
        for (Iterator<PackageBlock> packages = table.iterator(); packages.hasNext();) {
            PackageBlock candidate = packages.next();
            if (packageName.equals(candidate.getName())) {
                if (pack != null) throw new IOException("Tabela de recursos ambígua");
                pack = candidate;
            }
        }
        if (pack == null && table.size() == 0) pack = table.newPackage(0x7f, packageName);
        if (pack == null && table.size() == 1) pack = table.getPackageBlockById(0x7f);
        if (pack == null) throw new IOException("Tabela de recursos não corresponde ao pacote do jogo");
        if (pack.getName() == null || pack.getName().isEmpty()) pack.setName(packageName);
        if (pack.getEntry("drawable", NAME) != null) throw new IOException("Recurso de ícone BigXR já existe no jogo");

        // ARSCLib represents an empty table as null; without resetting this flag
        // the new resource exists in memory but serializes to zero bytes.
        table.setNull(false);
        table.setCurrentPackage(pack);
        Entry entry = pack.getOrCreate("-nodpi", "drawable", NAME);
        entry.setValueAsString(PATH);
        return entry.getResourceId();
    }

    private IconResourceTable() { }
}
