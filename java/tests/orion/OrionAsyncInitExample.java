// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package tests.orion;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.CefSettings.CefInitializationMode;
import org.cef.OS;
import org.cef.browser.CefBrowser;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.time.LocalTime;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Manual Swing demo that contrasts {@link CefInitializationMode#LEGACY_EDT} and
 * {@link CefInitializationMode#DEDICATED_CEF_THREAD}.
 *
 * <p>While {@code N_Initialize} runs you should observe that, in
 * {@code DEDICATED_CEF_THREAD} mode, the live counter keeps ticking, the spinner
 * keeps spinning, the text field stays editable and the buttons stay clickable;
 * in {@code LEGACY_EDT} mode the whole window freezes for the duration of native
 * initialization.
 *
 * <p>The browser is created only after {@code initializeAsync()} completes, and
 * the EDT is never blocked with {@code join()}/{@code get()}.
 *
 * <p>Run with the JCEF native library directory on {@code -Djava.library.path}
 * and, on Linux/macOS, after {@link CefApp#startup(String[])}.
 */
public final class OrionAsyncInitExample {
    private final JFrame frame = new JFrame("Orion JCEF async-init demo");
    private final JLabel counterLabel = new JLabel("Counter: 0");
    private final JLabel clockLabel = new JLabel();
    private final Spinner spinner = new Spinner();
    private final JTextField editable =
            new JTextField("Type here during initialization to prove the EDT is responsive");
    private final JProgressBar progress = new JProgressBar();
    private final JComboBox<CefInitializationMode> modeBox =
            new JComboBox<>(CefInitializationMode.values());
    private final JButton initButton = new JButton("Initialize JCEF");
    private final JButton pokeButton = new JButton("Click me during init");
    private final JLabel status = new JLabel("Idle. Pick a mode and initialize.");
    private final JPanel browserHost = new JPanel(new BorderLayout());

    private int counter = 0;
    private CefApp cefApp;

    private OrionAsyncInitExample(String[] startupArgs) {
        modeBox.setSelectedItem(
                (OS.isWindows() || OS.isLinux()) ? CefInitializationMode.DEDICATED_CEF_THREAD
                                                 : CefInitializationMode.LEGACY_EDT);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel row1 = new JPanel(new GridLayout(1, 3, 8, 0));
        row1.add(counterLabel);
        row1.add(clockLabel);
        row1.add(spinner);

        JPanel row2 = new JPanel(new BorderLayout(8, 0));
        row2.add(new JLabel("Mode: "), BorderLayout.WEST);
        row2.add(modeBox, BorderLayout.CENTER);

        JPanel row3 = new JPanel(new GridLayout(1, 2, 8, 0));
        row3.add(initButton);
        row3.add(pokeButton);

        progress.setIndeterminate(false);
        progress.setStringPainted(true);
        progress.setString("not started");

        top.add(row1);
        top.add(row2);
        top.add(editable);
        top.add(row3);
        top.add(progress);
        top.add(status);

        browserHost.setPreferredSize(new Dimension(900, 480));
        browserHost.add(new JLabel("Browser appears here once initialized.", JLabel.CENTER),
                BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(browserHost, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdownAndExit();
            }
        });
        frame.setSize(920, 640);
        frame.setLocationRelativeTo(null);

        // Liveness proof: a Swing Timer on the EDT. If the EDT is blocked (legacy
        // mode during init) these updates visibly stall.
        new Timer(100, e -> {
            counter++;
            counterLabel.setText("Counter: " + counter);
            clockLabel.setText("Clock: " + LocalTime.now().withNano(0));
            spinner.tick();
        }).start();

        pokeButton.addActionListener(e -> status.setText(
                "Button clicked at " + LocalTime.now().withNano(0) + " (EDT responsive)"));
        initButton.addActionListener(e -> startInitialization(startupArgs));
    }

    private void startInitialization(String[] startupArgs) {
        initButton.setEnabled(false);
        modeBox.setEnabled(false);
        CefInitializationMode mode = (CefInitializationMode) modeBox.getSelectedItem();

        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        settings.initialization_mode = mode;

        status.setText("Initializing in " + mode + " ...");
        progress.setIndeterminate(true);
        progress.setString("initializing (" + mode + ")");

        final long started = System.nanoTime();
        cefApp = CefApp.getInstance(startupArgs, settings);
        System.out.println("[demo] resolved mode = " + cefApp.getInitializationMode());

        // Never block the EDT: react on the future's completion instead.
        cefApp.initializeAsync().whenComplete((app, error) -> {
            long ms = (System.nanoTime() - started) / 1_000_000L;
            SwingUtilities.invokeLater(() -> {
                progress.setIndeterminate(false);
                if (error != null) {
                    progress.setString("failed");
                    status.setText("Initialization FAILED: " + rootMessage(error));
                    return;
                }
                progress.setValue(100);
                progress.setString("initialized in " + ms + " ms");
                status.setText("Initialized in " + ms + " ms on " + app.getInitializationMode()
                        + ". Creating browser...");
                createBrowser(app);
            });
        });
    }

    private void createBrowser(CefApp app) {
        try {
            CefClient client = app.createClient();
            CefBrowser browser = client.createBrowser("https://www.google.com", false, false);
            browserHost.removeAll();
            browserHost.add(browser.getUIComponent(), BorderLayout.CENTER);
            browserHost.revalidate();
            browserHost.repaint();
            status.setText("Browser ready.");
        } catch (RuntimeException ex) {
            status.setText("Browser creation failed: " + rootMessage(ex));
        }
    }

    private void shutdownAndExit() {
        status.setText("Shutting down...");
        if (cefApp != null) {
            // dispose() returns immediately; native shutdown runs on the owner
            // thread. Give it a brief moment, then exit.
            cefApp.dispose();
        }
        new Timer(1200, e -> {
            frame.dispose();
            System.exit(0);
        }) {
            { setRepeats(false); }
        }.start();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    /** A tiny CPU-free spinner that advances one step per {@link #tick()}. */
    private static final class Spinner extends JComponent {
        private int angle = 0;

        Spinner() {
            setPreferredSize(new Dimension(28, 28));
        }

        void tick() {
            angle = (angle + 30) % 360;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.GRAY);
            int s = Math.min(getWidth(), getHeight()) - 6;
            g2.translate(getWidth() / 2.0, getHeight() / 2.0);
            g2.rotate(Math.toRadians(angle));
            g2.drawArc(-s / 2, -s / 2, s, s, 0, 300);
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        // On Linux/macOS this must run before any Swing/Xlib usage.
        CefApp.startup(args);
        SwingUtilities.invokeLater(() -> {
            OrionAsyncInitExample demo = new OrionAsyncInitExample(args);
            demo.frame.setVisible(true);
        });
    }
}
