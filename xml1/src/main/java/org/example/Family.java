package org.example;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class Family {
    private static final Pattern SOURCE_ID = Pattern.compile("P[0-9]+");

    final Map<String, Person> people = new TreeMap<>();
    final List<String> issues = new ArrayList<>();
    private final List<PersonXml.Entry> deferredEntries = new ArrayList<>();
    private final List<Claim> deferredReferences = new ArrayList<>();
    private Map<String, List<Person>> names;
    private int entries;
    private int identifiedEntries;
    private int nameMatches;
    private int contextualMatches;
    private int redundantEntries;
    private int inferredSiblingPairs;
    private String declaredCount;

    private record Claim(Person subject, PersonXml.Ref ref) {}

    static final class Person {
        final String id;
        final TreeSet<String> first = new TreeSet<>();
        final TreeSet<String> last = new TreeSet<>();
        final EnumSet<PersonXml.Gender> explicitGender = EnumSet.noneOf(PersonXml.Gender.class);
        final EnumSet<PersonXml.Gender> roleGender = EnumSet.noneOf(PersonXml.Gender.class);
        final Set<Integer> childCounts = new TreeSet<>();
        final Set<Integer> siblingCounts = new TreeSet<>();
        final Set<PersonXml.Ref> refs = new LinkedHashSet<>();
        final Map<PersonXml.Kind, Set<Person>> relations = new EnumMap<>(PersonXml.Kind.class);
        boolean noSpouse;

        Person(String id) {
            this.id = id;
        }

        Set<Person> related(PersonXml.Kind kind) {
            return relations.getOrDefault(kind, Set.of());
        }

        boolean connect(PersonXml.Kind kind, Person other) {
            boolean added = relations.computeIfAbsent(kind, key -> new TreeSet<>(Comparator.comparing((Person p) -> p.id)))
                .add(other);
            if (added) {
                other.relations.computeIfAbsent(kind.reverse(), key -> new TreeSet<>(Comparator.comparing((Person p) -> p.id)))
                    .add(this);
            }
            return added;
        }

        PersonXml.Gender gender() {
            Set<PersonXml.Gender> known = explicitGender.isEmpty() ? roleGender : explicitGender;
            return known.size() == 1 ? known.iterator().next() : PersonXml.Gender.UNKNOWN;
        }

        Integer limit(PersonXml.Kind kind) {
            return switch (kind) {
                case PARENT -> 2;
                case SPOUSE -> noSpouse ? 0 : 1;
                case CHILD -> single(childCounts);
                case SIBLING -> single(siblingCounts);
            };
        }

        void merge(PersonXml.Entry entry) {
            first.addAll(entry.first);
            last.addAll(entry.last);
            explicitGender.addAll(entry.genders);
            childCounts.addAll(entry.children);
            siblingCounts.addAll(entry.siblings);
            noSpouse |= entry.noSpouse;
        }
    }

    void readIdentified(PersonXml.Entry entry) {
        entries++;
        for (String problem : entry.problems) {
            issues.add("line " + entry.line + ": " + problem);
        }
        if (entry.ids.size() == 1) {
            identifiedEntries++;
            Person person = people.computeIfAbsent(entry.ids.get(0), Person::new);
            person.merge(entry);
            person.refs.addAll(entry.refs);
        } else if (entry.ids.size() > 1) {
            issues.add("line " + entry.line + ": conflicting person IDs " + entry.ids);
        }
    }

    void indexNames() {
        names = new HashMap<>();
        for (Person person : people.values()) {
            String name = fullName(person.first, person.last);
            if (name != null) {
                names.computeIfAbsent(name, key -> new ArrayList<>()).add(person);
            }
        }
    }

    void readWithoutId(PersonXml.Entry entry) {
        if (!entry.ids.isEmpty()) {
            return;
        }
        List<Person> matches = candidates(entry);
        if (matches.size() == 1) {
            nameMatches++;
            Person person = matches.get(0);
            person.merge(entry);
            person.refs.addAll(entry.refs);
        } else {
            deferredEntries.add(entry);
        }
    }

    void finish(String inputCount) {
        declaredCount = inputCount;
        indexNames();
        for (Person person : people.values()) {
            for (PersonXml.Ref ref : person.refs) {
                takeReference(person, ref);
            }
            person.refs.clear();
        }

        boolean changed;
        do {
            changed = false;
            for (Iterator<Claim> it = deferredReferences.iterator(); it.hasNext();) {
                Claim claim = it.next();
                Person target = chooseTarget(claim.subject, claim.ref);
                if (target != null) {
                    link(claim.subject, claim.ref, target);
                    it.remove();
                    changed = true;
                }
            }
            for (Iterator<PersonXml.Entry> it = deferredEntries.iterator(); it.hasNext();) {
                PersonXml.Entry entry = it.next();
                List<Person> matches = candidates(entry);
                Person subject = chooseSubject(entry, matches);
                if (subject != null) {
                    subject.merge(entry);
                    for (PersonXml.Ref ref : entry.refs) {
                        takeReference(subject, ref);
                    }
                    it.remove();
                    contextualMatches++;
                    changed = true;
                }
            }
            if (changed) {
                indexNames();
            }
        } while (changed);

        inferSiblings();
        for (Claim claim : new LinkedHashSet<>(deferredReferences)) {
            List<Person> targets = targets(claim.ref);
            String status = targets.isEmpty() ? "unresolved" : "ambiguous or conflicting";
            issues.add(claim.subject.id + ": " + status + " " + claim.ref.kind() + " reference \""
                + claim.ref.value() + "\"" + (targets.isEmpty() ? "" : " candidates " + ids(targets)));
        }
        for (PersonXml.Entry entry : deferredEntries) {
            List<Person> matches = candidates(entry);
            if (!matches.isEmpty() && matches.stream().allMatch(p -> alreadyKnown(p, entry))) {
                redundantEntries++;
            } else {
                String name = fullName(entry.first, entry.last);
                issues.add("line " + entry.line + ": unresolved person fragment"
                    + (name == null ? "" : " for \"" + name + "\"")
                    + (matches.isEmpty() ? " (no matching ID)" : " candidates " + ids(matches)));
            }
        }
        validate();
    }

    private List<Person> candidates(PersonXml.Entry entry) {
        String name = fullName(entry.first, entry.last);
        return name == null ? List.of() : names.getOrDefault(name, List.of());
    }

    private static String fullName(Collection<String> first, Collection<String> last) {
        String given = single(first);
        String family = single(last);
        return given == null || family == null ? null : given + " " + family;
    }

    private static <T> T single(Collection<T> values) {
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private List<Person> targets(PersonXml.Ref ref) {
        Person idMatch = people.get(ref.value());
        if (idMatch != null) {
            return List.of(idMatch);
        }
        if (ref.explicitId() || SOURCE_ID.matcher(ref.value()).matches()) {
            return List.of();
        }
        return names.getOrDefault(ref.value(), List.of());
    }

    private void takeReference(Person subject, PersonXml.Ref ref) {
        Person target = single(targets(ref));
        if (target != null) {
            link(subject, ref, target);
        } else {
            deferredReferences.add(new Claim(subject, ref));
        }
    }

    private void link(Person subject, PersonXml.Ref ref, Person target) {
        if (subject == target) {
            issues.add(subject.id + ": self " + ref.kind() + " reference");
            return;
        }
        subject.connect(ref.kind(), target);
        if (ref.gender() != PersonXml.Gender.UNKNOWN) {
            target.roleGender.add(ref.gender());
        }
    }

    private Person chooseTarget(Person subject, PersonXml.Ref ref) {
        List<Person> matches = targets(ref);
        if (matches.size() == 1) {
            return matches.get(0);
        }
        List<Person> linked = matches.stream().filter(subject.related(ref.kind())::contains).toList();
        if (linked.size() == 1) {
            return linked.get(0);
        }
        List<Person> eligible = matches.stream().filter(target -> target != subject
            && compatibleGender(target, ref.gender())
            && canAdd(subject, ref.kind(), target)
            && canAdd(target, ref.kind().reverse(), subject)).toList();
        if (eligible.size() == 1) {
            return eligible.get(0);
        }
        if (ref.kind() == PersonXml.Kind.SPOUSE) {
            List<Person> coParents = eligible.stream().filter(target -> sharesChild(subject, target)).toList();
            Person coParent = single(coParents);
            if (coParent != null && hasReverseSpouseClaim(subject, coParent)) {
                return coParent;
            }
        }
        return null;
    }

    private static boolean canAdd(Person subject, PersonXml.Kind kind, Person target) {
        Integer limit = subject.limit(kind);
        Set<Person> current = subject.related(kind);
        return limit == null || current.contains(target) || current.size() < limit;
    }

    private static boolean sharesChild(Person subject, Person target) {
        return subject.related(PersonXml.Kind.CHILD).stream().anyMatch(target.related(PersonXml.Kind.CHILD)::contains);
    }

    private boolean hasReverseSpouseClaim(Person subject, Person target) {
        // A shared child can distinguish candidates when an ID-less spouse claim also points back.
        for (PersonXml.Entry entry : deferredEntries) {
            if (candidates(entry).contains(target) && entry.refs.stream().anyMatch(ref ->
                    ref.kind() == PersonXml.Kind.SPOUSE && single(targets(ref)) == subject)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatibleGender(Person p, PersonXml.Gender gender) {
        return gender == PersonXml.Gender.UNKNOWN || p.gender() == PersonXml.Gender.UNKNOWN || p.gender() == gender;
    }

    private Person chooseSubject(PersonXml.Entry entry, List<Person> matches) {
        if (matches.size() == 1) {
            return matches.get(0);
        }
        List<Person> eligible = new ArrayList<>();
        List<Person> corroborated = new ArrayList<>();
        for (Person person : matches) {
            if (!compatibleFragment(person, entry)) {
                continue;
            }
            eligible.add(person);
            if (entry.refs.stream().anyMatch(ref -> {
                Person target = single(targets(ref));
                return target != null && person.related(ref.kind()).contains(target);
            })) {
                corroborated.add(person);
            }
        }
        return eligible.size() == 1 ? eligible.get(0) : single(corroborated);
    }

    private boolean compatibleFragment(Person person, PersonXml.Entry entry) {
        if (entry.genders.stream().anyMatch(gender -> !compatibleGender(person, gender))
            || !compatibleCount(person, PersonXml.Kind.CHILD, entry.children)
            || !compatibleCount(person, PersonXml.Kind.SIBLING, entry.siblings)
            || (entry.noSpouse && !person.related(PersonXml.Kind.SPOUSE).isEmpty())) {
            return false;
        }
        Map<PersonXml.Kind, Set<Person>> combined = new EnumMap<>(PersonXml.Kind.class);
        for (PersonXml.Ref ref : entry.refs) {
            Person target = single(targets(ref));
            if (target == null) {
                continue;
            }
            if (target == person || !compatibleGender(target, ref.gender())
                || !canAdd(target, ref.kind().reverse(), person)) {
                return false;
            }
            combined.computeIfAbsent(ref.kind(), kind -> new HashSet<>(person.related(kind))).add(target);
        }
        for (Map.Entry<PersonXml.Kind, Set<Person>> relation : combined.entrySet()) {
            PersonXml.Kind kind = relation.getKey();
            Integer limit = switch (kind) {
                case CHILD -> entry.children.isEmpty() ? person.limit(kind) : single(entry.children);
                case SIBLING -> entry.siblings.isEmpty() ? person.limit(kind) : single(entry.siblings);
                case SPOUSE -> entry.noSpouse ? Integer.valueOf(0) : person.limit(kind);
                case PARENT -> person.limit(kind);
            };
            if (limit != null && relation.getValue().size() > limit) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatibleCount(Person person, PersonXml.Kind kind, List<Integer> counts) {
        Integer known = person.limit(kind);
        return counts.stream().allMatch(count -> (known == null || known.equals(count))
            && person.related(kind).size() <= count);
    }

    private boolean alreadyKnown(Person person, PersonXml.Entry entry) {
        return person.first.containsAll(entry.first) && person.last.containsAll(entry.last)
            && entry.genders.stream().allMatch(gender -> person.gender() == gender)
            && alreadyKnownCounts(person, PersonXml.Kind.CHILD, entry.children, person.childCounts)
            && alreadyKnownCounts(person, PersonXml.Kind.SIBLING, entry.siblings, person.siblingCounts)
            && (!entry.noSpouse || person.noSpouse)
            && entry.refs.stream().allMatch(ref -> {
                Person target = single(targets(ref));
                return target != null && person.related(ref.kind()).contains(target)
                    && (ref.gender() == PersonXml.Gender.UNKNOWN || target.roleGender.contains(ref.gender()));
            });
    }

    private static boolean alreadyKnownCounts(Person person, PersonXml.Kind kind, List<Integer> counts,
                                              Set<Integer> claimed) {
        int resolved = person.related(kind).size();
        return counts.stream().allMatch(count -> count == resolved && (claimed.isEmpty() || claimed.contains(count)));
    }

    private static String ids(Collection<Person> people) {
        return people.stream().map(p -> p.id).collect(Collectors.joining(", ", "[", "]"));
    }

    private void inferSiblings() {
        for (Person parent : people.values()) {
            List<Person> children = new ArrayList<>(parent.related(PersonXml.Kind.CHILD));
            for (int i = 0; i < children.size(); i++) {
                for (int j = i + 1; j < children.size(); j++) {
                    if (children.get(i).connect(PersonXml.Kind.SIBLING, children.get(j))) {
                        inferredSiblingPairs++;
                    }
                }
            }
        }
    }

    private void validate() {
        if (!declaredCount.isEmpty()) {
            try {
                if (PersonXml.parseCount(declaredCount) != people.size()) {
                    issues.add("people count: declared " + declaredCount + ", consolidated " + people.size());
                }
            } catch (NumberFormatException ex) {
                issues.add("invalid people count " + declaredCount);
            }
        }
        for (Person person : people.values()) {
            validateField(person, "first name", person.first);
            validateField(person, "last name", person.last);
            validateField(person, "explicit gender", person.explicitGender);
            validateField(person, "gender from relationship labels", person.roleGender);
            if (!person.explicitGender.isEmpty() && !person.explicitGender.containsAll(person.roleGender)) {
                issues.add(person.id + ": explicit gender " + person.explicitGender
                    + " disagrees with relationship labels " + person.roleGender);
            }
            validateCount(person, PersonXml.Kind.CHILD, person.childCounts);
            validateCount(person, PersonXml.Kind.SIBLING, person.siblingCounts);
            if (person.related(PersonXml.Kind.PARENT).size() > 2) {
                issues.add(person.id + ": more than two parents " + ids(person.related(PersonXml.Kind.PARENT)));
            }
            Set<Person> spouses = person.related(PersonXml.Kind.SPOUSE);
            if (spouses.size() > 1) {
                issues.add(person.id + ": multiple spouses " + ids(spouses));
            }
            if (person.noSpouse && !spouses.isEmpty()) {
                issues.add(person.id + ": explicit NONE spouse conflicts with " + ids(spouses));
            }
            for (Person sibling : person.related(PersonXml.Kind.SIBLING)) {
                if (person.related(PersonXml.Kind.PARENT).contains(sibling)) {
                    issues.add(person.id + ": " + sibling.id + " is both a parent and a sibling");
                }
            }
        }
        validateAncestry();
    }

    private void validateField(Person person, String field, Collection<?> values) {
        if (values.size() > 1) {
            issues.add(person.id + ": conflicting " + field + " values " + values);
        }
    }

    private void validateCount(Person person, PersonXml.Kind kind, Set<Integer> declared) {
        String label = kind == PersonXml.Kind.CHILD ? "children" : "siblings";
        int found = person.related(kind).size();
        if (!declared.isEmpty() && (declared.size() != 1 || !declared.contains(found))) {
            issues.add(person.id + ": " + label + " count declared " + declared + ", resolved " + found);
        }
    }

    private void validateAncestry() {
        Map<Person, Integer> remainingParents = new HashMap<>();
        ArrayDeque<Person> ready = new ArrayDeque<>();
        for (Person person : people.values()) {
            int parentCount = person.related(PersonXml.Kind.PARENT).size();
            remainingParents.put(person, parentCount);
            if (parentCount == 0) {
                ready.add(person);
            }
        }
        int traversed = 0;
        while (!ready.isEmpty()) {
            Person parent = ready.remove();
            traversed++;
            for (Person child : parent.related(PersonXml.Kind.CHILD)) {
                if (remainingParents.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (traversed < people.size()) {
            List<Person> sample = people.values().stream().filter(p -> remainingParents.get(p) > 0).limit(10).toList();
            issues.add("ancestry contains a parent–child cycle; IDs in or downstream of cycles include " + ids(sample));
        }
    }

    void report(PrintWriter out) {
        out.println("Task X1 validation");
        out.println("Input person entries: " + entries);
        out.println("Entries with a single ID: " + identifiedEntries);
        out.println("ID-less entries matched by unique name: " + nameMatches);
        out.println("ID-less entries matched using context: " + contextualMatches);
        out.println("ID-less entries whose facts were confirmed for every matching ID: " + redundantEntries);
        out.println("Declared people: " + (declaredCount.isEmpty() ? "(unspecified)" : declaredCount));
        out.println("Consolidated people: " + people.size());
        out.println("Additional sibling pairs inferred from a shared parent: " + inferredSiblingPairs);
        out.println("People with a children count attributable to their ID: " + people.values().stream()
            .filter(p -> !p.childCounts.isEmpty()).count());
        out.println("People with a siblings count attributable to their ID: " + people.values().stream()
            .filter(p -> !p.siblingCounts.isEmpty()).count());
        out.println("Validation issues: " + issues.size());
        issues.forEach(out::println);
        out.flush();
    }
}
