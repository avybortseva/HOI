package org.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLStreamException;

public final class TaskX1 {
    private TaskX1() {}

    public static void main(String[] args) {
        int result = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (result != 0) {
            System.exit(result);
        }
    }

    static int run(String[] args, PrintWriter stdout, PrintWriter stderr) {
        if (args.length != 2) {
            stderr.println("Usage: java -cp build/classes/java/main org.example.TaskX1 INPUT.xml|INPUT.zip OUTPUT.xml");
            return 2;
        }
        try {
            Path input = Path.of(args[0]);
            Path output = destination(Path.of(args[1]));
            requireDifferent(input, output);
            Family family = load(input);
            replaceFile(output, partial -> {
                try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(partial))) {
                    PersonXml.write(family, stream);
                }
            });
            family.report(stdout);
            return family.issues.isEmpty() ? 0 : 1;
        } catch (IOException | XMLStreamException | IllegalArgumentException ex) {
            stderr.println("Task X1: " + ex.getMessage());
            return 2;
        }
    }

    private static Path destination(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            return absolute.toRealPath();
        }
        return absolute.getParent().toRealPath().resolve(absolute.getFileName());
    }

    @FunctionalInterface
    private interface FileWriter {
        void write(Path file) throws IOException, XMLStreamException;
    }

    private static void replaceFile(Path destination, FileWriter writer) throws IOException, XMLStreamException {
        Path partial = Files.createTempFile(destination.getParent(), ".task-x1-", ".tmp");
        try {
            writer.write(partial);
            try {
                Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static void requireDifferent(Path first, Path second) throws IOException {
        if (first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
                || (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second))) {
            throw new IOException("Input and output must be different files");
        }
    }

    static Family load(Path path) throws IOException, XMLStreamException {
        Family family = new Family();
        try (Source source = new Source(path)) {
            String declared;
            try (InputStream in = source.open()) {
                declared = PersonXml.read(in, family::readIdentified, family.issues::add);
            }
            family.indexNames();
            try (InputStream in = source.open()) {
                PersonXml.read(in, family::readWithoutId, ignored -> {});
            }
            family.finish(declared);
        }
        return family;
    }

    private static final class Source implements AutoCloseable {
        private final Path path;
        private final ZipFile zip;
        private final ZipEntry entry;

        Source(Path path) throws IOException {
            this.path = path;
            if (path.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                ZipFile archive = new ZipFile(path.toFile());
                List<? extends ZipEntry> xml = archive.stream()
                    .filter(e -> !e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".xml")).toList();
                if (xml.size() != 1) {
                    archive.close();
                    throw new IOException("ZIP must contain exactly one XML file, found " + xml.size());
                }
                zip = archive;
                entry = xml.get(0);
            } else {
                zip = null;
                entry = null;
            }
        }

        InputStream open() throws IOException {
            return new BufferedInputStream(zip == null ? Files.newInputStream(path) : zip.getInputStream(entry));
        }

        @Override
        public void close() throws IOException {
            if (zip != null) {
                zip.close();
            }
        }
    }
}
