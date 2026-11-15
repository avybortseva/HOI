package org.example;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

final class PersonXml {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ID_LIST = Pattern.compile("P[0-9]+(?: P[0-9]+)*");

    enum Kind {
        SPOUSE, PARENT, CHILD, SIBLING;

        Kind reverse() {
            return switch (this) {
                case PARENT -> CHILD;
                case CHILD -> PARENT;
                default -> this;
            };
        }

        String tag(Gender gender) {
            return switch (this) {
                case SPOUSE -> gender == Gender.MALE ? "husband" : gender == Gender.FEMALE ? "wife" : "spouse";
                case PARENT -> gender == Gender.MALE ? "father" : gender == Gender.FEMALE ? "mother" : "parent";
                case CHILD -> gender == Gender.MALE ? "son" : gender == Gender.FEMALE ? "daughter" : "child";
                case SIBLING -> gender == Gender.MALE ? "brother" : gender == Gender.FEMALE ? "sister" : "sibling";
            };
        }
    }

    enum Gender {
        MALE, FEMALE, UNKNOWN;

        static Gender parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "m", "male" -> MALE;
                case "f", "female" -> FEMALE;
                default -> UNKNOWN;
            };
        }
    }

    record Ref(Kind kind, Gender gender, String value, boolean explicitId) {}

    static final class Entry {
        final int line;
        final List<String> ids = new ArrayList<>(2);
        final List<String> first = new ArrayList<>(2);
        final List<String> last = new ArrayList<>(2);
        final List<Gender> genders = new ArrayList<>(1);
        final List<Integer> children = new ArrayList<>(1);
        final List<Integer> siblings = new ArrayList<>(1);
        final List<Ref> refs = new ArrayList<>(4);
        final List<String> problems = new ArrayList<>(0);
        boolean noSpouse;

        Entry(int line) {
            this.line = line;
        }
    }

    private record Element(String tag, Map<String, String> attributes, String text, List<Element> children) {
        String attribute(String name) {
            return attributes.getOrDefault(name, "");
        }

        String value() {
            for (String key : List.of("id", "ref", "value", "val", "name")) {
                String value = attribute(key);
                if (!value.isEmpty()) {
                    return value;
                }
            }
            return text;
        }
    }

    static String read(InputStream input, Consumer<Entry> consume, Consumer<String> problem) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(input);
        try {
            nextTag(reader);
            if (reader.getEventType() != XMLStreamConstants.START_ELEMENT || !reader.getLocalName().equals("people")) {
                throw new XMLStreamException("Expected <people> as the root element", reader.getLocation());
            }
            String declaredCount = normalize(reader.getAttributeValue(null, "count"));
            while (nextTag(reader) == XMLStreamConstants.START_ELEMENT) {
                int line = reader.getLocation().getLineNumber();
                Element element = readElement(reader);
                if (element.tag.equals("person")) {
                    consume.accept(entry(element, line));
                } else {
                    problem.accept("line " + line + ": unexpected root child <" + element.tag + ">");
                }
            }
            while (reader.hasNext()) {
                reader.next(); // Validate the rest of the document, including trailing content.
            }
            return declaredCount;
        } finally {
            reader.close();
        }
    }

    private static int nextTag(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int type = reader.next();
            switch (type) {
                case XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT -> {
                    return type;
                }
                case XMLStreamConstants.DTD -> throw new XMLStreamException("DOCTYPE is unsupported", reader.getLocation());
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (!reader.isWhiteSpace()) {
                        throw new XMLStreamException("Unexpected text", reader.getLocation());
                    }
                }
                default -> { }
            }
        }
        return reader.getEventType();
    }

    private static Element readElement(XMLStreamReader reader) throws XMLStreamException {
        String tag = reader.getLocalName();
        Map<String, String> attributes = reader.getAttributeCount() == 0 ? Map.of() : new LinkedHashMap<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            attributes.put(reader.getAttributeLocalName(i), normalize(reader.getAttributeValue(i)));
        }
        StringBuilder text = new StringBuilder();
        List<Element> children = new ArrayList<>();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> children.add(readElement(reader));
                case XMLStreamConstants.END_ELEMENT -> {
                    return new Element(tag, attributes, normalize(text.toString()), children);
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                    text.append(reader.getText());
                default -> { }
            }
        }
        throw new XMLStreamException("Unclosed <" + tag + ">", reader.getLocation());
    }

    private static Entry entry(Element element, int line) {
        Entry entry = new Entry(line);
        add(entry.ids, element.attribute("id"));
        parseFullName(entry, element.attribute("name"));
        for (Element field : element.children) {
            String value = field.value();
            switch (field.tag) {
                case "id" -> add(entry.ids, value);
                case "fullname", "full-name", "name" -> {
                    if (field.children.isEmpty()) {
                        parseFullName(entry, value);
                    }
                    for (Element part : field.children) {
                        switch (part.tag) {
                            case "first", "firstname", "first-name" -> add(entry.first, part.value());
                            case "family", "surname", "family-name", "last-name" -> add(entry.last, part.value());
                            default -> entry.problems.add("unexpected name field <" + part.tag + ">");
                        }
                    }
                }
                case "firstname", "first-name" -> add(entry.first, value);
                case "surname", "family-name", "last-name" -> add(entry.last, value);
                case "gender" -> {
                    Gender gender = Gender.parse(value);
                    if (gender != Gender.UNKNOWN) {
                        add(entry.genders, gender);
                    } else if (!placeholder(value)) {
                        entry.problems.add("invalid gender " + value);
                    }
                }
                case "children-number", "siblings-number" ->
                    count(entry, field.tag, value, field.tag.equals("children-number") ? entry.children : entry.siblings);
                case "parents", "children", "siblings" -> {
                    Kind kind = switch (field.tag) {
                        case "parents" -> Kind.PARENT;
                        case "children" -> Kind.CHILD;
                        default -> Kind.SIBLING;
                    };
                    if (field.attributes.containsKey("count")) {
                        if (kind == Kind.CHILD) {
                            count(entry, field.tag, field.attribute("count"), entry.children);
                        } else if (kind == Kind.SIBLING) {
                            count(entry, field.tag, field.attribute("count"), entry.siblings);
                        }
                    }
                    if (kind == Kind.SIBLING && ID_LIST.matcher(value).matches()) {
                        for (String id : value.split(" ")) {
                            entry.refs.add(new Ref(kind, Gender.UNKNOWN, id, true));
                        }
                    } else if (!value.isEmpty()) {
                        reference(entry, field, kind, Gender.UNKNOWN);
                    }
                    for (Element child : field.children) {
                        relationship(entry, child, kind);
                    }
                }
                default -> relationship(entry, field, null);
            }
        }
        return entry;
    }

    private static void relationship(Entry entry, Element field, Kind container) {
        Kind kind = switch (field.tag) {
            case "spouce", "spouse", "husband", "wife" -> Kind.SPOUSE;
            case "parent", "father", "mother" -> Kind.PARENT;
            case "child", "son", "daughter" -> Kind.CHILD;
            case "sibling", "brother", "sister" -> Kind.SIBLING;
            default -> null;
        };
        if (kind == null || (container != null && kind != container)) {
            entry.problems.add("unexpected relationship field <" + field.tag + ">");
            return;
        }
        Gender gender = switch (field.tag) {
            case "husband", "father", "son", "brother" -> Gender.MALE;
            case "wife", "mother", "daughter", "sister" -> Gender.FEMALE;
            default -> Gender.UNKNOWN;
        };
        reference(entry, field, kind, gender);
    }

    private static void reference(Entry entry, Element field, Kind kind, Gender gender) {
        String value = field.value();
        if (kind == Kind.SPOUSE && (value.equalsIgnoreCase("NONE") || field.attribute("status").equals("none"))) {
            entry.noSpouse = true;
        } else if (!placeholder(value)) {
            boolean id = !field.attribute("id").isEmpty() || !field.attribute("ref").isEmpty();
            entry.refs.add(new Ref(kind, gender, value, id));
        }
    }

    private static boolean placeholder(String value) {
        return value.isEmpty() || value.equalsIgnoreCase("UNKNOWN") || value.equalsIgnoreCase("NONE");
    }

    private static void parseFullName(Entry entry, String name) {
        int split = name.lastIndexOf(' ');
        if (split >= 0) {
            add(entry.first, name.substring(0, split));
            add(entry.last, name.substring(split + 1));
        } else {
            add(entry.first, name);
        }
    }

    private static void count(Entry entry, String field, String value, List<Integer> counts) {
        try {
            add(counts, parseCount(value));
        } catch (NumberFormatException ex) {
            entry.problems.add("invalid " + field + " count " + (value.isEmpty() ? "(empty)" : value));
        }
    }

    static int parseCount(String value) {
        int count = Integer.parseInt(value);
        if (count < 0) {
            throw new NumberFormatException("Negative count");
        }
        return count;
    }

    private static <T> void add(List<T> values, T value) {
        if (!value.equals("") && !values.contains(value)) {
            values.add(value);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : SPACE.matcher(text).replaceAll(" ").strip();
    }

    static void write(Family family, OutputStream out) throws XMLStreamException {
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
        try {
            writer.writeStartDocument("UTF-8", "1.0");
            start(writer, 0, "people");
            writer.writeAttribute("count", Integer.toString(family.people.size()));
            for (Family.Person person : family.people.values()) {
                start(writer, 1, "person");
                writer.writeAttribute("id", person.id);
                if (person.first.size() == 1) {
                    text(writer, "first-name", person.first.first());
                }
                if (person.last.size() == 1) {
                    text(writer, "last-name", person.last.first());
                }
                if (person.gender() != Gender.UNKNOWN) {
                    text(writer, "gender", person.gender().name().toLowerCase(Locale.ROOT));
                }
                if (person.noSpouse && person.related(Kind.SPOUSE).isEmpty()) {
                    indent(writer, 2);
                    writer.writeEmptyElement("spouse");
                    writer.writeAttribute("status", "none");
                }
                refs(writer, person, Kind.SPOUSE, 2);
                group(writer, person, Kind.PARENT, "parents");
                group(writer, person, Kind.CHILD, "children");
                group(writer, person, Kind.SIBLING, "siblings");
                end(writer, 1);
            }
            end(writer, 0);
            writer.writeCharacters("\n");
            writer.writeEndDocument();
            writer.flush();
        } finally {
            writer.close();
        }
    }

    private static void group(XMLStreamWriter writer, Family.Person person, Kind kind, String tag)
            throws XMLStreamException {
        indent(writer, 2);
        int count = person.related(kind).size();
        if (count == 0) {
            writer.writeEmptyElement(tag);
            writer.writeAttribute("count", "0");
        } else {
            writer.writeStartElement(tag);
            writer.writeAttribute("count", Integer.toString(count));
            refs(writer, person, kind, 3);
            end(writer, 2);
        }
    }

    private static void refs(XMLStreamWriter writer, Family.Person person, Kind kind, int depth)
            throws XMLStreamException {
        for (Family.Person target : person.related(kind)) {
            indent(writer, depth);
            writer.writeEmptyElement(kind.tag(target.gender()));
            writer.writeAttribute("ref", target.id);
        }
    }

    private static void start(XMLStreamWriter writer, int depth, String tag) throws XMLStreamException {
        indent(writer, depth);
        writer.writeStartElement(tag);
    }

    private static void end(XMLStreamWriter writer, int depth) throws XMLStreamException {
        indent(writer, depth);
        writer.writeEndElement();
    }

    private static void text(XMLStreamWriter writer, String tag, String value) throws XMLStreamException {
        start(writer, 2, tag);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    private static void indent(XMLStreamWriter writer, int depth) throws XMLStreamException {
        writer.writeCharacters("\n" + "  ".repeat(depth));
    }
}
