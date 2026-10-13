package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
    public static final int countSortThreads = 4;
    private static final long stepDelay = 300;
    private static final long insideStepDelay = 300;

    private static final boolean enableTimer = true;
    private static final long timerMS = 60000;

    public static final AtomicLong stepCounter = new AtomicLong(0);

    static void main(String[] args) {
        ConcurrentLinkedList linkedList = new ConcurrentLinkedList();

        for (int i = 0; i < countSortThreads; i++) {
            Thread newSortThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    int stepsInPass = linkedList.bubbleSort1(stepDelay, insideStepDelay);
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

        if (enableTimer) {
            Thread timerThread = new Thread(() -> {
                try {
                    Thread.sleep(timerMS);
                    System.out.println("Step count: " + stepCounter.get());
                    System.exit(0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            timerThread.setDaemon(true);
            timerThread.start();
        }

        Scanner sc = new Scanner(System.in);
        System.out.println("Enter any number of lines:");

        while (true) {
            String input = sc.nextLine();

            if (input.isEmpty()) {
                System.out.println("List status:");
                int count = 0;

                for (String s : linkedList) {
                    System.out.println("[" + ++count + "] " + s);
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
}
