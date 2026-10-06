package org.example;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class SynchronizedListMain {
    public static final int countSortThreads = 3;
    private static final long stepDelay = 100;
    private static final long insideStepDelay = 100;

    public static final AtomicLong stepCounter = new AtomicLong(0);

    static void main(String[] args) {
        List<String> linkedList = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < countSortThreads; i++) {
            Thread newSortThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    int stepsInPass = bubbleSort2(linkedList, stepDelay, insideStepDelay);
                    stepCounter.addAndGet(stepsInPass);

                    if (stepsInPass == 0) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            newSortThread.setDaemon(true);
            newSortThread.start();
        }

        Scanner sc = new Scanner(System.in);
        System.out.println("Enter any number of lines:");

        while (true) {
            String input = sc.nextLine();

            if (input.isEmpty()) {
                System.out.println("List status:");
                int count = 0;

                synchronized (linkedList) {
                    for (String s : linkedList) {
                        System.out.println("[" + ++count + "] " + s);
                    }
                }
                if (count == 0) {
                    System.out.println("The list is empty(");
                }
                System.out.println("All steps: " + stepCounter.get());
            } else {
                List<String> chunks = new ArrayList<>();
                int length = input.length();
                for (int i = 0; i < length; i+=80) {
                    chunks.add(input.substring(i, Math.min(i + 80, length)));
                }

                for (int i = chunks.size() - 1; i >= 0 ; i--) {
                    linkedList.addFirst(chunks.get(i));
                }
                System.out.println("Count of new lines: " + chunks.size());
            }
        }
    }

    private static int bubbleSort2(List<String> list, long stepDelay, long insideStepDelay) {
        int steps = 0;
        int size;

        synchronized (list) {
            size = list.size();
        }

        for (int i = 0; i < size - 1; i++) {
            synchronized (list) {
                if (i + 1 >= list.size()) {
                    break;
                }
                steps++;
                sleepFor(insideStepDelay);

                String s1 = list.get(i);
                String s2 = list.get(i + 1);

                if (s1.compareTo(s2) > 0) {
                    list.set(i, s2);
                    list.set(i + 1, s1);
                }
            }
            sleepFor(stepDelay);
        }
        return steps;
    }

    private static void sleepFor(long ms) {
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
