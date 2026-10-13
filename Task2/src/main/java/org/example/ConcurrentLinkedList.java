package org.example;

import java.util.Iterator;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLinkedList implements Iterable<String> {
    private final Node head;

    public ConcurrentLinkedList() {
        this.head = new Node(null);
    }

    private static class Node {
        final String value;
        volatile Node next;
        final Lock lock = new ReentrantLock();

        private Node(String value) {
            this.value = value;
        }
    }

    public void addFirst(String value) {
        Node newNode = new Node(value);
        head.lock.lock();
        try {
            newNode.next = head.next;
            head.next = newNode;
        } finally {
            head.lock.unlock();
        }
    }

    @Override
    public Iterator<String> iterator() {
        return new Iterator<String>() {
            private Node current = head.next;

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public String next() {
                String val = current.value;
                current = current.next;
                return val;
            }
        };
    }

    public int bubbleSort1(long stepDelay, long insideStepDelay) {
        int steps = 0;

        head.lock.lock();
        Node prev = head;
        Node cur = prev.next;
        if (cur == null) {
            prev.lock.unlock();
            return 0;
        }

        cur.lock.lock();
        boolean headLocked = true;

        try {

            while (true) {
                Node next = cur.next;
                if (next == null) {
                    break;
                }

                next.lock.lock();

                try {
                    steps++;
                    sleepFor(insideStepDelay);

                    if (cur.value.compareTo(next.value) > 0) {

                        prev.next = next;
                        if (headLocked) {
                            head.lock.unlock();
                            headLocked = false;
                        }
                        cur.next = next.next;
                        next.next = cur;

                        Node oldPrev = prev;
                        prev = next;

                        if (oldPrev != head) {
                            oldPrev.lock.unlock();
                        }
                    } else {
                        if (headLocked) {
                            head.lock.unlock();
                            headLocked = false;
                        }
                        Node oldPrev = prev;
                        prev = cur;
                        cur = next;

                        if (oldPrev != head) {
                            oldPrev.lock.unlock();
                        }
                    }
                    sleepFor(stepDelay);
                } catch (Exception e) {
                    next.lock.unlock();
                    throw new RuntimeException(e);
                }
            }
        } finally {
            cur.lock.unlock();
            if (headLocked || prev != head) {
                prev.lock.unlock();
            }
        }
        return steps;
    }

    private void sleepFor(long ms) {
        if (ms < 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
