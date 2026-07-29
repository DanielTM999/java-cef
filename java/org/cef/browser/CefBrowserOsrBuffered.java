// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DropTarget;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/**
 * An off-screen rendered browser that paints into a lightweight Swing
 * component using a software {@link BufferedImage} instead of a heavyweight
 * {@code GLCanvas}. Because the resulting component is a normal
 * double-buffered {@link JComponent}, it composes cleanly with Swing tab
 * switches, split panes and repaints without the flicker inherent to
 * heavyweight AWT peers.
 *
 * The visibility of this class is "package". To create a new CefBrowser
 * instance, please use CefBrowserFactory.
 */
class CefBrowserOsrBuffered extends CefBrowser_N implements CefRenderHandler, CefScrollConfigurable {
    private static final int DEFAULT_WHEEL_SCROLL_PIXELS = 100;

    private volatile int scrollPixelsPerNotch_ = DEFAULT_WHEEL_SCROLL_PIXELS;

    private final boolean isTransparent_;
    private BufferedCanvas canvas_;
    private boolean justCreated_ = false;
    private Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1); // Work around CEF issue #1437.
    private Point screenPoint_ = new Point(0, 0);
    private double scaleFactor_ = 1.0;
    private int depth = 32;
    private int depth_per_component = 8;

    private final Object paintLock_ = new Object();
    private BufferedImage mainImage_;
    private int[] mainData_;
    private int mainWidth_ = 0;
    private int mainHeight_ = 0;

    private BufferedImage popupImage_;
    private int[] popupData_;
    private int popupWidth_ = 0;
    private int popupHeight_ = 0;
    private boolean popupVisible_ = false;
    private Rectangle popupRect_ = new Rectangle(0, 0, 0, 0);

    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners =
            new CopyOnWriteArrayList<>();

    CefBrowserOsrBuffered(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserSettings settings) {
        this(client, url, transparent, context, null, null, settings);
    }

    private CefBrowserOsrBuffered(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserOsrBuffered parent, Point inspectAt,
            CefBrowserSettings settings) {
        super(client, url, context, parent, inspectAt, settings);
        isTransparent_ = transparent;
        canvas_ = new BufferedCanvas();
    }

    @Override
    public void createImmediately() {
        justCreated_ = true;
        createBrowserIfRequired();
    }

    @Override
    public Component getUIComponent() {
        return canvas_;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    public void setScrollPixelsPerNotch(int pixelsPerNotch) {
        scrollPixelsPerNotch_ = Math.max(1, pixelsPerNotch);
    }

    @Override
    public int getScrollPixelsPerNotch() {
        return scrollPixelsPerNotch_;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return new CefBrowserOsrBuffered(
                client, url, isTransparent_, context, (CefBrowserOsrBuffered) this, inspectAt, null);
    }

    private void createBrowserIfRequired() {
        if (getNativeRef("CefBrowser") == 0) {
            if (getParentBrowser() != null) {
                createDevTools(getParentBrowser(), getClient(), 0, true, isTransparent_, null,
                        getInspectAt());
            } else {
                createBrowser(getClient(), 0, getUrl(), true, isTransparent_, null,
                        getRequestContext());
            }
        } else if (justCreated_) {
            notifyAfterParentChanged();
            setFocus(true);
            justCreated_ = false;
        }
    }

    private void notifyAfterParentChanged() {
        getClient().onAfterParentChanged(this);
    }

    private void updateScaleFactor() {
        GraphicsConfiguration config = canvas_.getGraphicsConfiguration();
        double factor = config == null ? 1.0 : config.getDefaultTransform().getScaleX();
        if (factor <= 0) {
            factor = 1.0;
        }
        if (factor != scaleFactor_) {
            scaleFactor_ = factor;
            depth = 32;
            depth_per_component = 8;
            wasResized(canvas_.getWidth(), canvas_.getHeight());
        }
    }

    // CefRenderHandler

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return browser_rect_;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        screenInfo.Set(scaleFactor_, depth, depth_per_component, false, browser_rect_.getBounds(),
                browser_rect_.getBounds());
        return true;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point origin = currentScreenOrigin();
        return new Point(origin.x + viewPoint.x, origin.y + viewPoint.y);
    }

    private Point currentScreenOrigin() {
        BufferedCanvas canvas = canvas_;
        if (canvas != null && canvas.isShowing()) {
            try {
                Point live = canvas.getLocationOnScreen();
                screenPoint_ = live;
                return new Point(live);
            } catch (IllegalComponentStateException ignored) {
            }
        }
        return new Point(screenPoint_);
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        popupVisible_ = show;
        if (!show) {
            popupRect_.setBounds(0, 0, 0, 0);
            invalidate();
            repaintCanvas();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        if (size.width <= 0 || size.height <= 0) {
            return;
        }
        popupRect_ = clampPopupToView(size);
    }

    private Rectangle clampPopupToView(Rectangle original) {
        Rectangle rc = new Rectangle(original);
        int viewWidth = browser_rect_.width;
        int viewHeight = browser_rect_.height;
        if (rc.x < 0) rc.x = 0;
        if (rc.y < 0) rc.y = 0;
        if (rc.x + rc.width > viewWidth) rc.x = viewWidth - rc.width;
        if (rc.y + rc.height > viewHeight) rc.y = viewHeight - rc.height;
        if (rc.x < 0) rc.x = 0;
        if (rc.y < 0) rc.y = 0;
        return rc;
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        synchronized (paintLock_) {
            if (popup) {
                storePopup(dirtyRects, buffer, width, height);
            } else {
                storeMain(dirtyRects, buffer, width, height);
            }
        }
        repaintCanvas();

        if (!onPaintListeners.isEmpty()) {
            CefPaintEvent paintEvent =
                    new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
            for (Consumer<CefPaintEvent> l : onPaintListeners) {
                l.accept(paintEvent);
            }
        }
    }

    private void storeMain(Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        boolean resized = width != mainWidth_ || height != mainHeight_ || mainImage_ == null;
        if (resized) {
            mainImage_ = new BufferedImage(width, height,
                    isTransparent_ ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_ARGB);
            mainData_ = ((DataBufferInt) mainImage_.getRaster().getDataBuffer()).getData();
            mainWidth_ = width;
            mainHeight_ = height;
        }
        IntBuffer src = asIntBuffer(buffer);
        if (resized) {
            src.get(mainData_, 0, Math.min(mainData_.length, src.remaining()));
        } else {
            copyDirtyRows(src, mainData_, width, height, dirtyRects);
        }
    }

    private void storePopup(Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        boolean resized = width != popupWidth_ || height != popupHeight_ || popupImage_ == null;
        if (resized) {
            popupImage_ = new BufferedImage(width, height,
                    isTransparent_ ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_ARGB);
            popupData_ = ((DataBufferInt) popupImage_.getRaster().getDataBuffer()).getData();
            popupWidth_ = width;
            popupHeight_ = height;
        }
        IntBuffer src = asIntBuffer(buffer);
        if (resized) {
            src.get(popupData_, 0, Math.min(popupData_.length, src.remaining()));
        } else {
            copyDirtyRows(src, popupData_, width, height, dirtyRects);
        }
    }

    private static IntBuffer asIntBuffer(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.order(ByteOrder.LITTLE_ENDIAN);
        return duplicate.asIntBuffer();
    }

    private static void copyDirtyRows(
            IntBuffer src, int[] dst, int width, int height, Rectangle[] dirtyRects) {
        if (dirtyRects == null || dirtyRects.length == 0) {
            src.get(dst, 0, Math.min(dst.length, src.remaining()));
            return;
        }
        for (Rectangle rect : dirtyRects) {
            int x = Math.max(0, rect.x);
            int y = Math.max(0, rect.y);
            int w = Math.min(rect.width, width - x);
            int h = Math.min(rect.height, height - y);
            if (w <= 0 || h <= 0) {
                continue;
            }
            for (int row = y; row < y + h; row++) {
                int offset = row * width + x;
                src.position(offset);
                src.get(dst, offset, w);
            }
        }
    }

    private void repaintCanvas() {
        // Component.repaint() is thread-safe and coalesces through the
        // RepaintManager, so it can be called directly from the CEF UI thread.
        // Avoiding an invokeLater per frame keeps scrolling fluid.
        BufferedCanvas canvas = canvas_;
        if (canvas != null) {
            canvas.repaint();
        }
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.clear();
        onPaintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.remove(listener);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, final int cursorType) {
        SwingUtilities.invokeLater(() -> {
            BufferedCanvas canvas = canvas_;
            if (canvas == null || canvas.isAutoScrolling()) {
                // While middle-button autoscroll is active the native layer
                // reports the default cursor (the panning types are not mapped),
                // so keep the four-way cursor we set ourselves.
                return;
            }
            try {
                canvas.setCursor(Cursor.getPredefinedCursor(cursorType));
            } catch (RuntimeException ignored) {
                canvas.setCursor(Cursor.getDefaultCursor());
            }
        });
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        int action = getDndAction(mask);
        MouseEvent triggerEvent =
                new MouseEvent(canvas_, MouseEvent.MOUSE_DRAGGED, 0, 0, x, y, 0, false);
        DragGestureEvent ev = new DragGestureEvent(
                new SyntheticDragGestureRecognizer(canvas_, action, triggerEvent), action,
                new Point(x, y), new ArrayList<>(Arrays.asList(triggerEvent)));

        DragSource.getDefaultDragSource().startDrag(ev, /*dragCursor=*/null,
                new StringSelection(dragData.getFragmentText()), new DragSourceAdapter() {
                    @Override
                    public void dragDropEnd(DragSourceDropEvent dsde) {
                        dragSourceEndedAt(dsde.getLocation(), action);
                        dragSourceSystemDragEnded();
                    }
                });
        return true;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {}

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        synchronized (paintLock_) {
            if (mainImage_ == null) {
                return CompletableFuture.completedFuture(
                        new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
            }
            BufferedImage copy =
                    new BufferedImage(mainWidth_, mainHeight_, mainImage_.getType());
            Graphics2D g = copy.createGraphics();
            g.drawImage(mainImage_, 0, 0, null);
            g.dispose();

            if (!nativeResolution && scaleFactor_ != 1.0) {
                int w = (int) (mainWidth_ / scaleFactor_);
                int h = (int) (mainHeight_ / scaleFactor_);
                BufferedImage resized = new BufferedImage(
                        Math.max(1, w), Math.max(1, h), mainImage_.getType());
                Graphics2D rg = resized.createGraphics();
                rg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                rg.drawImage(copy, 0, 0, resized.getWidth(), resized.getHeight(), null);
                rg.dispose();
                return CompletableFuture.completedFuture(resized);
            }
            return CompletableFuture.completedFuture(copy);
        }
    }

    private static int getDndAction(int mask) {
        int action = DnDConstants.ACTION_NONE;
        if ((mask & CefDragData.DragOperations.DRAG_OPERATION_COPY)
                == CefDragData.DragOperations.DRAG_OPERATION_COPY) {
            action = DnDConstants.ACTION_COPY;
        } else if ((mask & CefDragData.DragOperations.DRAG_OPERATION_MOVE)
                == CefDragData.DragOperations.DRAG_OPERATION_MOVE) {
            action = DnDConstants.ACTION_MOVE;
        } else if ((mask & CefDragData.DragOperations.DRAG_OPERATION_LINK)
                == CefDragData.DragOperations.DRAG_OPERATION_LINK) {
            action = DnDConstants.ACTION_LINK;
        }
        return action;
    }

    private static final class SyntheticDragGestureRecognizer extends DragGestureRecognizer {
        SyntheticDragGestureRecognizer(Component c, int action, MouseEvent triggerEvent) {
            super(new DragSource(), c, action);
            appendEvent(triggerEvent);
        }

        @Override
        protected void registerListeners() {}

        @Override
        protected void unregisterListeners() {}
    }

    @SuppressWarnings("serial")
    private final class BufferedCanvas extends JComponent {
        private boolean added_ = false;
        private boolean autoScroll_ = false;

        boolean isAutoScrolling() {
            return autoScroll_;
        }

        private void setAutoScrollCursor(boolean on) {
            autoScroll_ = on;
            setCursor(on ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                         : Cursor.getDefaultCursor());
        }

        BufferedCanvas() {
            setFocusable(true);
            setRequestFocusEnabled(true);
            // Let TAB / arrow / Enter reach the page instead of being consumed by
            // Swing focus traversal.
            setFocusTraversalKeysEnabled(false);
            setOpaque(true);
            setBackground(Color.WHITE);
            setDoubleBuffered(true);
            installListeners();
            new DropTarget(this, new CefDropTargetListener(CefBrowserOsrBuffered.this));
        }

        private MouseWheelEvent invertWheel(MouseWheelEvent e) {
            // The native SendMouseWheelEvent forwards the raw unit count (~3) with
            // no sign flip and no pixel scaling, so OSR scrolls the wrong way and
            // only a few pixels per notch. Flip the sign and scale to a pixel-sized
            // block delta so it matches the platform wheel feel.
            double rotation = e.getPreciseWheelRotation();
            if (rotation == 0.0) {
                rotation = e.getWheelRotation();
            }
            int pixels = scrollPixelsPerNotch_;
            int delta = (int) Math.round(-rotation * pixels);
            if (delta == 0) {
                int sign = e.getWheelRotation() >= 0 ? 1 : -1;
                delta = -sign * pixels;
            }
            return new MouseWheelEvent(this, e.getID(), e.getWhen(), e.getModifiersEx(),
                    e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(),
                    e.isPopupTrigger(), MouseWheelEvent.WHEEL_BLOCK_SCROLL, 0, delta);
        }

        private void installListeners() {
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    updateGeometry();
                }

                @Override
                public void componentMoved(ComponentEvent e) {
                    updateScreenPoint();
                }

                @Override
                public void componentShown(ComponentEvent e) {
                    updateGeometry();
                }
            });

            MouseListener mouseListener = new MouseListener() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (e.getButton() == MouseEvent.BUTTON2) {
                        // Middle click toggles Chromium autoscroll; mirror its
                        // four-way cursor since the native panning cursor is not
                        // forwarded in OSR mode.
                        setAutoScrollCursor(!autoScroll_);
                    } else if (autoScroll_) {
                        setAutoScrollCursor(false);
                    }
                    sendMouseEvent(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    sendMouseEvent(e);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    sendMouseEvent(e);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    sendMouseEvent(e);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    sendMouseEvent(e);
                }
            };
            addMouseListener(mouseListener);

            addMouseMotionListener(new MouseMotionListener() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    sendMouseEvent(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    sendMouseEvent(e);
                }
            });

            addMouseWheelListener(new MouseWheelListener() {
                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    sendMouseWheelEvent(invertWheel(e));
                }
            });

            addKeyListener(new KeyListener() {
                @Override
                public void keyTyped(KeyEvent e) {
                    sendKeyEvent(e);
                }

                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE && autoScroll_) {
                        setAutoScrollCursor(false);
                    }
                    sendKeyEvent(e);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    sendKeyEvent(e);
                }
            });

            addFocusListener(new FocusListener() {
                @Override
                public void focusLost(FocusEvent e) {
                    setFocus(false);
                }

                @Override
                public void focusGained(FocusEvent e) {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    setFocus(true);
                }
            });
        }

        private void updateGeometry() {
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            browser_rect_.setBounds(0, 0, width, height);
            updateScreenPoint();
            updateScaleFactor();
            wasResized(width, height);
        }

        private void updateScreenPoint() {
            if (isShowing()) {
                screenPoint_ = getLocationOnScreen();
            }
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (!added_) {
                updateScaleFactor();
                notifyAfterParentChanged();
                added_ = true;
            }
        }

        @Override
        public void removeNotify() {
            if (added_) {
                if (!isClosed()) {
                    notifyAfterParentChanged();
                }
                added_ = false;
            }
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());

                synchronized (paintLock_) {
                    if (mainImage_ == null) {
                        return;
                    }
                    double sf = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
                    AffineTransform saved = g2.getTransform();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.scale(1.0 / sf, 1.0 / sf);
                    g2.drawImage(mainImage_, 0, 0, null);
                    if (popupVisible_ && popupImage_ != null && popupRect_.width > 0) {
                        int px = (int) Math.round(popupRect_.x * sf);
                        int py = (int) Math.round(popupRect_.y * sf);
                        g2.drawImage(popupImage_, px, py, null);
                    }
                    g2.setTransform(saved);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
