package com.xrpocket.mobile;

import android.content.Context;
import android.net.Uri;
import android.content.pm.ServiceInfo;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.archive.FileInputSource;
import com.reandroid.archive.InputSource;
import com.reandroid.archive.writer.ApkFileWriter;
import com.reandroid.archive.writer.ZipAligner;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Experimental, local, fail-closed repackaging for a narrow class of ARM64 OpenXR APKs. */
final class ApkCreator {
    private static final long MAX_INPUT = 1_600_000_000L;
    private static final long MAX_COMBINED = 1_700_000_000L;
    private static final long MAX_OUTPUT = 1_800_000_000L;
    private static final int MAX_DEX = 128 * 1024 * 1024;
    private static final int MAX_NATIVE = 128 * 1024 * 1024;
    private static final int MAX_ICON = 8 * 1024 * 1024;
    private static final String BOOTSTRAP = "com.bigxr.embedded.BootstrapActivity";
    private static final String TARGET = "com.bigxr.embedded.TARGET_ACTIVITY";
    private static final String HAND_SERVICE = "com.bigxr.embedded.HandService";
    private static final String MONADO_SERVICE = "org.freedesktop.monado.ipc.MonadoService";
    private static final String BROKER_CLASS = "org.freedesktop.monado.phonexr.RuntimeBroker";
    private static final String LOADER = "lib/arm64-v8a/libopenxr_loader.so";
    private static final String OBB_PREFIX = "assets/bigxr_obb/";
    private static final Pattern DEX = Pattern.compile("classes([0-9]*)\\.dex");
    private static final Pattern PAYLOAD_SOURCE = Pattern.compile("(?:dex|native|assets)/[0-9]+\\.bin");
    private static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.CAMERA", "android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA", "android.permission.HIGH_SAMPLING_RATE_SENSORS"
    };

    static final class Result {
        final String packageName, sha256;
        final boolean hasObb;
        Result(String packageName, String sha256, boolean hasObb) {
            this.packageName = packageName; this.sha256 = sha256; this.hasObb = hasObb;
        }
    }

    static Result create(Context context, File input, String expectedPackage, String label, File icon,
                         Uri obbUri, String obbName, File output, Consumer<String> progress) throws Exception {
        if (!input.isFile() || input.length() <= 0 || input.length() > MAX_INPUT) throw new IOException("APK ausente ou maior que 1,6 GB");
        if (!PackageFiles.validPackage(expectedPackage) || expectedPackage.length() > BinaryPatches.OLD_RUNTIME.length())
            throw new IOException("Nome do pacote não cabe no runtime integrado");
        label = label == null ? "" : label.trim();
        if (label.isEmpty() || label.length() > 60) throw new IOException("Informe um nome de 1 a 60 caracteres");
        for (int i = 0; i < label.length(); i++) if (Character.isISOControl(label.charAt(i))) throw new IOException("Nome contém caracteres de controle");
        if (obbUri != null && !PackageFiles.validObb(obbName, expectedPackage)) throw new IOException("Nome do OBB não corresponde ao pacote");
        if (output.exists()) throw new IOException("Destino temporário já existe");
        if (context.getCacheDir().getUsableSpace() < 3 * input.length() + 128L * 1024 * 1024)
            throw new IOException("Espaço livre insuficiente para criar e assinar a cópia");

        File work = Files.createTempDirectory(context.getCacheDir().toPath(), "bigxr-build-").toFile();
        boolean success = false;
        try {
            progress.accept("Verificando APK e assinatura original…");
            TreeMap<Integer, ZipEntry> dexEntries = new TreeMap<>();
            Set<String> allClasses = new HashSet<>();
            try (ZipFile zip = new ZipFile(input)) {
                inspectZip(zip, dexEntries);
                LocalSigner.verifyOriginal(input);
                for (ZipEntry entry : dexEntries.values()) {
                    byte[] bytes = readBounded(zip.getInputStream(entry), MAX_DEX);
                    addClasses(allClasses, DexClasses.read(bytes));
                }
            }
            progress.accept("Preparando manifesto, ícone e runtime…");
            String broker = BinaryPatches.privateBroker(expectedPackage);
            File obb = null;
            String obbHash = null;
            if (obbUri != null) {
                obb = new File(work, "selected.obb");
                long maxObb = MAX_COMBINED - input.length();
                if (maxObb <= 0) throw new IOException("APK é grande demais para adicionar OBB");
                try (InputStream stream = context.getContentResolver().openInputStream(obbUri)) {
                    if (stream == null) throw new IOException("OBB selecionado não está disponível");
                    obbHash = copyAndHash(stream, obb, maxObb);
                }
                if (context.getCacheDir().getUsableSpace() < 3 * (input.length() + obb.length()) + 128L * 1024 * 1024)
                    throw new IOException("Espaço livre insuficiente para adicionar o OBB");
            }

            File unsigned = new File(work, "unsigned.apk");
            try (ApkModule module = ApkModule.loadApkFile(input); ZipFile zip = new ZipFile(input)) {
                module.setApkSignatureBlock(null);
                AndroidManifestBlock manifest = module.getAndroidManifest();
                if (manifest == null || !expectedPackage.equals(manifest.getPackageName())) throw new IOException("Pacote mudou durante a leitura");
                rejectSplit(manifest);
                String target = patchManifest(manifest, label, broker, obb != null);
                if (icon != null) addIcon(module, manifest, expectedPackage, icon);
                removeSignaturesAndOtherAbis(module, zip);
                ZipEntry loaderEntry = zip.getEntry(LOADER);
                if (loaderEntry == null) throw new IOException("O APK não contém libopenxr_loader.so para ARM64");
                byte[] patchedLoader = BinaryPatches.patchLoader(readBounded(zip.getInputStream(loaderEntry), MAX_NATIVE), broker);
                module.removeInputSource(LOADER);
                addBytes(module, LOADER, patchedLoader, loaderEntry.getMethod() == ZipEntry.STORED, true);
                addPayload(context, module, allClasses, dexEntries.lastKey() + 1, expectedPackage);
                if (obb != null) {
                    String destination = OBB_PREFIX + obbName;
                    if (module.containsFile(destination) || module.containsFile(OBB_PREFIX + "manifest.json"))
                        throw new IOException("O APK já contém dados reservados do BigXR");
                    FileInputSource source = new FileInputSource(obb, destination);
                    source.setUncompressed(true);
                    module.add(source); module.getUncompressedFiles().addPath(destination);
                    JSONObject description = new JSONObject().put("name", obbName).put("size", obb.length()).put("sha256", obbHash);
                    addBytes(module, OBB_PREFIX + "manifest.json", description.toString().getBytes(StandardCharsets.UTF_8), false, false);
                }
                module.refreshTable(); module.refreshManifest();
                ApkFileWriter writer = module.createApkFileWriter(unsigned);
                ZipAligner alignment = new ZipAligner(); alignment.setDefaultAlignment(4);
                alignment.setFileAlignment(ZipAligner.PREDICATE_NATIVE_LIBS, 16384);
                writer.setZipAligner(alignment);
                progress.accept("Gerando APK de " + target + "…");
                writer.write();
            }
            if (unsigned.length() <= 0 || unsigned.length() > MAX_OUTPUT) throw new IOException("APK criado excede o limite de 1,8 GB");
            progress.accept("Assinando e conferindo a cópia…");
            LocalSigner.sign(unsigned, output);
            if (output.length() > MAX_OUTPUT) throw new IOException("APK assinado excede o limite de 1,8 GB");
            String digest = sha256(output);
            success = true;
            return new Result(expectedPackage, digest, obb != null);
        } finally {
            deleteTree(work);
            if (!success) output.delete();
        }
    }

    private static void inspectZip(ZipFile zip, TreeMap<Integer, ZipEntry> dex) throws IOException {
        Set<String> names = new HashSet<>();
        long total = 0; int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement(); String name = entry.getName();
            if (++count > 100_000 || name.length() > 1024 || name.startsWith("/") || name.contains("\\")
                    || name.contains("\0") || java.util.Arrays.asList(name.split("/", -1)).contains("..") || !names.add(name))
                throw new IOException("APK contém caminhos inseguros ou entradas duplicadas");
            if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || (total += entry.getSize()) > 4_000_000_000L)
                throw new IOException("Conteúdo do APK é grande demais ou inválido");
            if ((name.equals("AndroidManifest.xml") || name.equals("resources.arsc")) && entry.getSize() > MAX_DEX)
                throw new IOException("Manifesto ou recursos grandes demais");
            Matcher match = DEX.matcher(name);
            if (match.matches()) {
                String number = match.group(1); int index;
                try { index = number.isEmpty() ? 1 : Integer.parseInt(number); }
                catch (NumberFormatException error) { throw new IOException("Nome DEX inválido"); }
                if (index < 1 || index > 128 || (!number.isEmpty() && (index < 2 || number.startsWith("0")))
                        || entry.getSize() > MAX_DEX || dex.put(index, entry) != null) throw new IOException("Sequência DEX inválida");
            }
        }
        if (!names.contains("AndroidManifest.xml") || dex.isEmpty() || dex.firstKey() != 1 || dex.lastKey() != dex.size())
            throw new IOException("APK sem manifesto ou sequência DEX completa");
        if (!names.contains(LOADER)) throw new IOException("O APK não contém o loader OpenXR ARM64 esperado");
        for (String name : names) if (name.startsWith(OBB_PREFIX)) throw new IOException("O APK já contém dados reservados do BigXR");
    }

    private static void rejectSplit(AndroidManifestBlock manifest) throws IOException {
        ResXmlElement root = manifest.getManifestElement();
        if (root == null) throw new IOException("Manifesto ausente");
        if (root.searchAttributeByName("split") != null || root.searchAttributeByName("configForSplit") != null
                || trueAttribute(root, "isFeatureSplit") || trueAttribute(root, "isSplitRequired")
                || root.getElement("uses-split") != null) throw new IOException("APKs divididos (split APK) ainda não são suportados");
        ResXmlElement app = manifest.getApplicationElement();
        if (app == null) throw new IOException("Manifesto sem aplicação");
        for (Iterator<ResXmlElement> it = app.getElements("meta-data"); it.hasNext();) {
            ResXmlElement item = it.next();
            if ("com.android.vending.splits.required".equals(androidName(item)) && trueAttribute(item, "value"))
                throw new IOException("Este jogo exige APKs adicionais (split APK)");
        }
    }
    private static boolean trueAttribute(ResXmlElement element, String name) {
        ResXmlAttribute value = element.searchAttributeByName(name);
        return value != null && ((value.getValueType() == ValueType.BOOLEAN && value.getValueAsBoolean())
                || "true".equalsIgnoreCase(value.getValueAsString()));
    }
    private static String androidName(ResXmlElement element) {
        ResXmlAttribute attr = element.searchAttributeByResourceId(android.R.attr.name);
        return attr == null ? null : attr.getValueAsString();
    }
    private static String absoluteName(AndroidManifestBlock manifest, String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.startsWith(".")) return manifest.getPackageName() + name;
        if (!name.contains(".")) return manifest.getPackageName() + "." + name;
        return name;
    }
    private static boolean hasNamed(ResXmlElement element, String tag, String value) {
        Iterator<ResXmlElement> it = element.getElements(tag);
        while (it.hasNext()) if (value.equals(androidName(it.next()))) return true;
        return false;
    }
    private static void stringAttr(ResXmlElement element, String name, int id, String value) {
        element.getOrCreateAndroidAttribute(name, id).setValueAsString(value);
    }
    private static void boolAttr(ResXmlElement element, String name, int id, boolean value) {
        element.getOrCreateAndroidAttribute(name, id).setValueAsBoolean(value);
    }

    /** Do not introduce a hardware requirement when BigXR adds an optional camera. */
    static void markAddedCameraOptional(AndroidManifestBlock manifest) {
        ResXmlElement root = manifest.getManifestElement();
        boolean gameRequestedCamera = hasNamed(root, "uses-permission", "android.permission.CAMERA")
                || hasNamed(root, "uses-permission-sdk-23", "android.permission.CAMERA")
                || hasNamed(root, "uses-permission-sdk-m", "android.permission.CAMERA");
        // Merely adding CAMERA would otherwise make Android infer that a rear
        // camera is required, even though the BigXR bootstrap can open without it.
        // Preserve any camera permission or feature already declared by the game.
        if (!gameRequestedCamera && !hasNamed(root, "uses-feature", "android.hardware.camera")) {
            ResXmlElement camera = root.newElement("uses-feature");
            stringAttr(camera, "name", android.R.attr.name, "android.hardware.camera");
            boolAttr(camera, "required", android.R.attr.required, false);
        }
    }

    private static String patchManifest(AndroidManifestBlock manifest, String label, String broker, boolean hasObb) throws IOException {
        ResXmlElement app = manifest.getApplicationElement();
        for (String tag : new String[]{"activity", "activity-alias", "service", "provider"}) {
            for (Iterator<ResXmlElement> it = app.getElements(tag); it.hasNext();) {
                ResXmlElement element = it.next(); String name = androidName(element);
                if (name != null) name = absoluteName(manifest, name);
                if (BOOTSTRAP.equals(name) || HAND_SERVICE.equals(name) || MONADO_SERVICE.equals(name) || BROKER_CLASS.equals(name))
                    throw new IOException("O APK já possui componentes BigXR/Monado conflitantes");
                ResXmlAttribute authority = element.searchAttributeByResourceId(android.R.attr.authorities);
                if (authority != null && authority.getValueAsString() != null) {
                    for (String existing : authority.getValueAsString().split(";"))
                        if (broker.equals(existing)) throw new IOException("Autoridade do broker já existe no jogo");
                }
            }
        }
        List<ResXmlElement> categories = new ArrayList<>(); Set<String> targets = new HashSet<>();
        Iterator<ResXmlElement> activities = manifest.getActivities(true);
        while (activities.hasNext()) {
            ResXmlElement activity = activities.next();
            for (Iterator<ResXmlElement> filters = activity.getElements("intent-filter"); filters.hasNext();) {
                ResXmlElement filter = filters.next();
                if (!hasNamed(filter, "action", "android.intent.action.MAIN")) continue;
                List<ResXmlElement> chosen = new ArrayList<>();
                for (Iterator<ResXmlElement> cats = filter.getElements("category"); cats.hasNext();) {
                    ResXmlElement cat = cats.next(); String name = androidName(cat);
                    if ("android.intent.category.LAUNCHER".equals(name) || "org.khronos.openxr.intent.category.IMMERSIVE_HMD".equals(name)) chosen.add(cat);
                }
                if (!chosen.isEmpty()) {
                    String name = androidName(activity);
                    if (name == null || name.isEmpty()) throw new IOException("Tela inicial sem nome");
                    targets.add(absoluteName(manifest, name)); categories.addAll(chosen);
                }
            }
        }
        if (targets.size() != 1) throw new IOException("Esperada uma única tela inicial Android/OpenXR; encontradas " + targets.size());
        for (ResXmlElement cat : categories) cat.getParentElement().remove(cat);
        String target = targets.iterator().next();
        manifest.setApplicationLabel(label);
        manifest.setExtractNativeLibs(true);
        Integer minSdk = manifest.getMinSdkVersion();
        if (minSdk == null || minSdk < 29) manifest.setMinSdkVersion(29);
        markAddedCameraOptional(manifest);
        for (String permission : REQUIRED_PERMISSIONS) {
            ResXmlElement uses = manifest.addUsesPermission(permission);
            if (uses.searchAttributeByResourceId(android.R.attr.maxSdkVersion) != null)
                throw new IOException("Permissão necessária tem limite de versão incompatível: " + permission);
        }
        if (hasObb && manifest.getUsesPermission("android.permission.WRITE_EXTERNAL_STORAGE") == null) {
            ResXmlElement permission = manifest.addUsesPermission("android.permission.WRITE_EXTERNAL_STORAGE");
            permission.getOrCreateAndroidAttribute("maxSdkVersion", android.R.attr.maxSdkVersion).setTypeAndData(ValueType.DEC, 29);
        }
        ResXmlElement bootstrap = manifest.getOrCreateActivity(BOOTSTRAP, false);
        boolAttr(bootstrap, "exported", android.R.attr.exported, true);
        stringAttr(bootstrap, "label", android.R.attr.label, label);
        bootstrap.getOrCreateAndroidAttribute("theme", android.R.attr.theme)
                .setTypeAndData(ValueType.REFERENCE, android.R.style.Theme_Material_NoActionBar);
        ResXmlElement meta = bootstrap.newElement("meta-data");
        stringAttr(meta, "name", android.R.attr.name, TARGET); stringAttr(meta, "value", android.R.attr.value, target);
        ResXmlElement obbMeta = bootstrap.newElement("meta-data");
        stringAttr(obbMeta, "name", android.R.attr.name, "com.bigxr.embedded.HAS_OBB");
        boolAttr(obbMeta, "value", android.R.attr.value, hasObb);
        ResXmlElement filter = bootstrap.newElement("intent-filter");
        stringAttr(filter.newElement("action"), "name", android.R.attr.name, "android.intent.action.MAIN");
        stringAttr(filter.newElement("category"), "name", android.R.attr.name, "android.intent.category.LAUNCHER");
        ResXmlElement provider = app.newElement("provider");
        stringAttr(provider, "name", android.R.attr.name, BROKER_CLASS);
        stringAttr(provider, "authorities", android.R.attr.authorities, broker);
        boolAttr(provider, "exported", android.R.attr.exported, false);
        ResXmlElement hands = app.newElement("service");
        stringAttr(hands, "name", android.R.attr.name, HAND_SERVICE);
        boolAttr(hands, "exported", android.R.attr.exported, false);
        stringAttr(hands, "process", android.R.attr.process, ":bigxr_hands");
        hands.getOrCreateAndroidAttribute("foregroundServiceType", android.R.attr.foregroundServiceType)
                .setTypeAndData(ValueType.HEX, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        ResXmlElement monado = app.newElement("service");
        stringAttr(monado, "name", android.R.attr.name, MONADO_SERVICE);
        boolAttr(monado, "exported", android.R.attr.exported, false);
        stringAttr(monado, "process", android.R.attr.process, ":bigxr_monado");
        ResXmlElement ipc = monado.newElement("intent-filter");
        stringAttr(ipc.newElement("action"), "name", android.R.attr.name, "org.freedesktop.monado.ipc.CONNECT");
        return target;
    }

    private static void addIcon(ApkModule module, AndroidManifestBlock manifest, String packageName, File icon) throws IOException {
        if (!icon.isFile() || icon.length() <= 0 || icon.length() > MAX_ICON) throw new IOException("Imagem do ícone inválida");
        TableBlock table = module.getTableBlock(false);
        if (table == null) {
            table = TableBlock.createEmpty();
            module.setTableBlock(table);
        }
        if (module.containsFile(IconResourceTable.PATH)) throw new IOException("Recurso de ícone BigXR já existe no jogo");
        int id = IconResourceTable.add(table, packageName);
        manifest.setIconResourceId(id);
        manifest.getApplicationElement().getOrCreateAndroidAttribute("roundIcon", android.R.attr.roundIcon)
                .setTypeAndData(ValueType.REFERENCE, id);
        addBytes(module, IconResourceTable.PATH, readBounded(new FileInputStream(icon), MAX_ICON), false, false);
    }

    private static void removeSignaturesAndOtherAbis(ApkModule module, ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName(); String upper = name.toUpperCase(Locale.ROOT);
            boolean signature = upper.equals("META-INF/MANIFEST.MF") || upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")
                    || upper.matches("META-INF/SIG-[^/]+");
            boolean otherAbi = name.startsWith("lib/") && !name.startsWith("lib/arm64-v8a/");
            if (signature || otherAbi) module.removeInputSource(name);
        }
    }

    private static void addPayload(Context context, ApkModule module, Set<String> classes, int firstDex, String packageName) throws Exception {
        JSONObject manifest = new JSONObject(new String(readBounded(context.getAssets().open("bigxr_payload/manifest.json"), 1024 * 1024), StandardCharsets.UTF_8));
        if (manifest.getInt("version") != 1) throw new IOException("Versão do suporte BigXR não reconhecida");
        for (String group : new String[]{"dex", "native", "assets"}) {
            JSONArray entries = manifest.getJSONArray(group);
            if (entries.length() == 0 || entries.length() > 100) throw new IOException("Suporte BigXR incompleto");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                String source = item.getString("source"), path = item.getString("path");
                if (!PAYLOAD_SOURCE.matcher(source).matches() || !source.startsWith(group + "/")) throw new IOException("Caminho do suporte inválido");
                if (group.equals("native") && (!path.startsWith("lib/arm64-v8a/") || !path.endsWith(".so") || path.substring("lib/arm64-v8a/".length()).contains("/")))
                    throw new IOException("Biblioteca do suporte inválida");
                if (group.equals("assets") && (!path.startsWith("assets/") || path.contains("..") || path.contains("\\")))
                    throw new IOException("Recurso do suporte inválido");
                long expectedSize = item.getLong("size");
                if (expectedSize <= 0 || expectedSize > MAX_DEX) throw new IOException("Arquivo de suporte grande demais");
                byte[] data = readBounded(context.getAssets().open("bigxr_payload/" + source), MAX_DEX);
                if (data.length != expectedSize || !item.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(data))))
                    throw new IOException("Arquivo de suporte BigXR corrompido: " + path);
                if (group.equals("dex")) {
                    addClasses(classes, DexClasses.read(data));
                    path = "classes" + firstDex++ + ".dex";
                } else if (path.equals("lib/arm64-v8a/libopenxr_monado.so") || path.equals("lib/arm64-v8a/libmonado-service.so")) {
                    data = BinaryPatches.patchRuntime(data, packageName);
                }
                if (module.containsFile(path)) {
                    // Sharing an identical asset or JNI library is safe; any other collision is rejected.
                    InputSource existing = module.getInputSource(path);
                    byte[] old = readBounded(existing.openStream(), MAX_DEX);
                    if (!java.util.Arrays.equals(old, data)) throw new IOException("Conflito com arquivo do jogo: " + path);
                } else addBytes(module, path, data, item.optBoolean("stored", false), false);
            }
        }
    }

    private static void addClasses(Set<String> existing, Set<String> incoming) throws IOException {
        for (String descriptor : incoming) if (!existing.add(descriptor))
            throw new IOException("Classe em conflito no APK: " + descriptor);
    }
    private static void addBytes(ApkModule module, String path, byte[] data, boolean stored, boolean allowOriginalPath) throws IOException {
        if (!allowOriginalPath && module.containsFile(path)) throw new IOException("Arquivo já existe no jogo: " + path);
        ByteInputSource source = new ByteInputSource(data, path); source.setUncompressed(stored);
        module.add(source);
        if (stored) module.getUncompressedFiles().addPath(path); else module.getUncompressedFiles().removePath(path);
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[65536]; int n, total = 0;
            while ((n = in.read(block)) != -1) {
                if (n == 0) continue;
                if (n > limit - total) throw new IOException("Arquivo interno grande demais");
                out.write(block, 0, n); total += n;
            }
            return out.toByteArray();
        }
    }
    private static String copyAndHash(InputStream input, File file, long limit) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0;
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] block = new byte[256 * 1024]; int n;
            while ((n = input.read(block)) != -1) {
                if (n == 0) continue;
                total += n; if (total > limit) throw new IOException("APK e OBB juntos excedem 1,7 GB");
                out.write(block, 0, n); digest.update(block, 0, n);
            }
            if (total == 0) throw new IOException("OBB está vazio");
            out.getFD().sync();
        }
        return hex(digest.digest());
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] block = new byte[256 * 1024]; int n;
            while ((n = in.read(block)) != -1) if (n > 0) digest.update(block, 0, n);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    private ApkCreator() { }
}
