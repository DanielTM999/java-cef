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
import java.awt.event.HierarchyEvent;
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
import javax.swing.Timer;

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
class CefBrowserOsrBuffered extends CefBrowser_N
        implements CefRenderHandler, CefScrollConfigurable, CefViewSizeHint {
    private static final int DEFAULT_WHEEL_SCROLL_PIXELS = 100;
    // Frame buffers grow in steps so a live resize reuses the same pixel array
    // instead of allocating a full-frame image (a G1 humongous object) per frame.
    private static final int BUFFER_GROWTH_STEP = 256;
    // At most one WasResized per frame interval while the component is being
    // dragged; the trailing call always delivers the final size.
    private static final int RESIZE_COALESCE_MS = 16;
    // After the last WasResized, how long to wait for a frame of the new size
    // before assuming the resize was lost and sending it again.
    private static final int RESIZE_VERIFY_MS = 150;
    private static final int MAX_RESIZE_RETRIES = 3;
    // While no frame at all has arrived since the last WasResized, keep waiting
    // (a slow or throttled renderer is not a lost resize) up to this budget.
    private static final long RESIZE_WAIT_BUDGET_NANOS = 3_000_000_000L;

    private volatile int scrollPixelsPerNotch_ = DEFAULT_WHEEL_SCROLL_PIXELS;

    private final boolean isTransparent_;
    private final boolean adoptedPopup_;
    private BufferedCanvas canvas_;
    private boolean justCreated_ = false;
    private Timer resizeTimer_;
    private Timer resizeVerifyTimer_;
    private int resizeRetries_ = 0;
    private long lastResizeSentNanos_ = 0L;
    // Immutable snapshot: written on the EDT, read on the CEF UI thread. Never
    // mutate it in place; replace the reference. 1x1 works around CEF issue #1437.
    private volatile Rectangle viewRect_ = new Rectangle(0, 0, 1, 1);
    private volatile int expectedFrameWidth_ = 0;
    private volatile int expectedFrameHeight_ = 0;
    private volatile int lastFrameWidth_ = 0;
    private volatile int lastFrameHeight_ = 0;
    private volatile long lastFrameNanos_ = 0L;
    private volatile long resizeSentNanos_ = 0L;
    private volatile boolean resizeApplied_ = true;
    private long resizeWaitStartNanos_ = 0L;
    private Point screenPoint_ = new Point(0, 0);
    private volatile double scaleFactor_ = 1.0;
    private int depth = 32;
    private int depth_per_component = 8;

    private final Object paintLock_ = new Object();
    private BufferedImage mainImage_;
    private int[] mainData_;
    private int mainStride_ = 0;
    private int mainWidth_ = 0;
    private int mainHeight_ = 0;

    private BufferedImage popupImage_;
    private int[] popupData_;
    private int popupStride_ = 0;
    private int popupWidth_ = 0;
    private int popupHeight_ = 0;
    private boolean popupVisible_ = false;
    private Rectangle popupRect_ = new Rectangle(0, 0, 0, 0);

    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners =
            new CopyOnWriteArrayList<>();

    CefBrowserOsrBuffered(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserSettings settings) {
        this(client, url, transparent, context, null, null, settings, false);
    }

    private CefBrowserOsrBuffered(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserOsrBuffered parent, Point inspectAt,
            CefBrowserSettings settings, boolean adoptedPopup) {
        super(client, url, context, parent, inspectAt, settings);
        isTransparent_ = transparent;
        adoptedPopup_ = adoptedPopup;
        canvas_ = new BufferedCanvas();
    }

    /**
     * Orion fork: a browser that never creates a native browser itself. The
     * native side binds the popup of another browser to it from
     * LifeSpanHandler::OnBeforePopup (see CefLifeSpanHandler.onBeforePopupBrowser).
     * The popup inherits the opener's request context, so none is kept here.
     */
    static CefBrowserOsrBuffered createPopupHost(
            CefClient client, String url, CefBrowserSettings settings) {
        return new CefBrowserOsrBuffered(client, url, false, null, null, null, settings, true);
    }

    @Override
    public void createImmediately() {
        justCreated_ = true;
        createBrowserIfRequired();
    }

    @Override
    void onNativeCreated() {
        if (!adoptedPopup_) {
            // WasResized calls made before the native browser was bound were
            // dropped, so the view may still be at the size CEF queried at
            // creation. Push the current geometry once the browser exists.
            SwingUtilities.invokeLater(() -> {
                BufferedCanvas canvas = canvas_;
                if (canvas != null && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
                    canvas.updateGeometry();
                } else {
                    Rectangle view = viewRect_;
                    wasResized(view.width, view.height);
                }
                invalidate();
            });
            return;
        }
        if (isCloseRequested()) {
            // The embedder closed the popup before Chromium created it; the
            // earlier close was a no-op because nothing was bound yet.
            forceNativeClose();
            return;
        }
        // The popup was created with whatever size CEF could query before it
        // was bound to this object; push the real view size now.
        SwingUtilities.invokeLater(() -> {
            BufferedCanvas canvas = canvas_;
            if (canvas != null && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
                canvas.updateGeometry();
            } else {
                Rectangle view = viewRect_;
                wasResized(view.width, view.height);
            }
            invalidate();
            if (justCreated_) {
                notifyAfterParentChanged();
                setFocus(true);
                justCreated_ = false;
            }
        });
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
    public void setViewSizeHint(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        BufferedCanvas canvas = canvas_;
        if (canvas != null && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
            return;
        }
        viewRect_ = new Rectangle(0, 0, width, height);
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return new CefBrowserOsrBuffered(
                client, url, isTransparent_, context, (CefBrowserOsrBuffered) this, inspectAt, null,
                false);
    }

    private void createBrowserIfRequired() {
        if (getNativeRef("CefBrowser") == 0) {
            if (adoptedPopup_) {
                // Bound asynchronously by the native popup; see onNativeCreated.
                return;
            }
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

    private void requestResize() {
        if (resizeTimer_ == null) {
            resizeTimer_ = new Timer(RESIZE_COALESCE_MS, e -> flushResize());
            resizeTimer_.setRepeats(false);
        }
        if (resizeTimer_.isRunning()) {
            return;
        }
        long elapsed = System.nanoTime() - lastResizeSentNanos_;
        if (elapsed >= RESIZE_COALESCE_MS * 1_000_000L) {
            flushResize();
        } else {
            resizeTimer_.start();
        }
    }

    private void flushResize() {
        resizeRetries_ = 0;
        long now = System.nanoTime();
        resizeWaitStartNanos_ = now;
        resizeSentNanos_ = now;
        resizeApplied_ = false;
        sendResize();
    }

    private void sendResize() {
        lastResizeSentNanos_ = System.nanoTime();
        Rectangle view = viewRect_;
        double sf = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
        expectedFrameWidth_ = (int) Math.ceil(view.width * sf);
        expectedFrameHeight_ = (int) Math.ceil(view.height * sf);
        wasResized(view.width, view.height);
        scheduleResizeVerify();
    }

    private void scheduleResizeVerify() {
        if (resizeVerifyTimer_ == null) {
            resizeVerifyTimer_ = new Timer(RESIZE_VERIFY_MS, e -> verifyResize());
            resizeVerifyTimer_.setRepeats(false);
        }
        resizeVerifyTimer_.restart();
    }

    /**
     * A resize can be lost (sent before the native browser was bound, or
     * swallowed while CEF held a previous resize). Left alone, Chromium keeps
     * laying the page out at the old size: the page looks cropped and cannot
     * scroll. Re-send until a frame of the expected size arrives. While no
     * frame at all has arrived since the last send, the renderer is just slow:
     * keep waiting (with an invalidate to request one) instead of re-sending.
     */
    private void verifyResize() {
        if (isClosed()) {
            return;
        }
        BufferedCanvas canvas = canvas_;
        if (canvas == null || !canvas.isShowing()) {
            return;
        }
        if (matchesExpectedFrame(lastFrameWidth_, lastFrameHeight_)) {
            return;
        }
        boolean frameSinceSend = lastFrameNanos_ >= lastResizeSentNanos_;
        if (!frameSinceSend
                && System.nanoTime() - resizeWaitStartNanos_ < RESIZE_WAIT_BUDGET_NANOS) {
            invalidate();
            scheduleResizeVerify();
            return;
        }
        if (resizeRetries_ >= MAX_RESIZE_RETRIES) {
            return;
        }
        resizeRetries_++;
        if (PaintStats.ENABLED) {
            System.out.printf("[JCEF OSR] resize retry %d: frame %dx%d, expected %dx%d%n",
                    resizeRetries_, lastFrameWidth_, lastFrameHeight_, expectedFrameWidth_,
                    expectedFrameHeight_);
        }
        sendResize();
        invalidate();
    }

    private boolean matchesExpectedFrame(int width, int height) {
        return Math.abs(width - expectedFrameWidth_) <= 1
                && Math.abs(height - expectedFrameHeight_) <= 1;
    }

    // CefRenderHandler

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return new Rectangle(viewRect_);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        Rectangle view = viewRect_;
        screenInfo.Set(scaleFactor_, depth, depth_per_component, false, view.getBounds(),
                view.getBounds());
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
        Rectangle view = viewRect_;
        int viewWidth = view.width;
        int viewHeight = view.height;
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
        long start = PaintStats.ENABLED ? System.nanoTime() : 0L;
        synchronized (paintLock_) {
            if (popup) {
                storePopup(dirtyRects, buffer, width, height);
            } else {
                storeMain(dirtyRects, buffer, width, height);
            }
        }
        if (PaintStats.ENABLED) {
            PaintStats.recordCopy(System.nanoTime() - start);
        }
        repaintCanvas(popup ? null : dirtyRects);

        if (!onPaintListeners.isEmpty()) {
            CefPaintEvent paintEvent =
                    new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
            for (Consumer<CefPaintEvent> l : onPaintListeners) {
                l.accept(paintEvent);
            }
        }
    }

    /**
     * CEF delivers BGRA bytes, i.e. little-endian 0xAARRGGBB ints. An opaque
     * page is stored as TYPE_INT_RGB (alpha ignored) so Java2D can use a plain
     * opaque blit instead of a per-pixel alpha blend on every frame.
     */
    private int imageType() {
        return isTransparent_ ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB;
    }

    private void storeMain(Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        boolean resized = width != mainWidth_ || height != mainHeight_ || mainImage_ == null;
        if (resized) {
            if (needsNewBuffer(mainImage_, width, height)) {
                mainImage_ = allocateBuffer(width, height);
                mainData_ = ((DataBufferInt) mainImage_.getRaster().getDataBuffer()).getData();
            }
            mainStride_ = mainImage_.getWidth();
            mainWidth_ = width;
            mainHeight_ = height;
            lastFrameWidth_ = width;
            lastFrameHeight_ = height;
        }
        lastFrameNanos_ = System.nanoTime();
        if (!resizeApplied_ && matchesExpectedFrame(width, height)) {
            resizeApplied_ = true;
            if (PaintStats.ENABLED) {
                System.out.printf("[JCEF OSR] resize %dx%d applied after %d ms%n", width, height,
                        (lastFrameNanos_ - resizeSentNanos_) / 1_000_000L);
            }
        }
        copyRows(asIntBuffer(buffer), mainData_, mainStride_, width, height,
                resized ? null : dirtyRects);
    }

    private void storePopup(Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        boolean resized = width != popupWidth_ || height != popupHeight_ || popupImage_ == null;
        if (resized) {
            if (needsNewBuffer(popupImage_, width, height)) {
                popupImage_ = allocateBuffer(width, height);
                popupData_ = ((DataBufferInt) popupImage_.getRaster().getDataBuffer()).getData();
            }
            popupStride_ = popupImage_.getWidth();
            popupWidth_ = width;
            popupHeight_ = height;
        }
        copyRows(asIntBuffer(buffer), popupData_, popupStride_, width, height,
                resized ? null : dirtyRects);
    }

    private static int roundUpToStep(int value) {
        return ((Math.max(1, value) + BUFFER_GROWTH_STEP - 1) / BUFFER_GROWTH_STEP)
                * BUFFER_GROWTH_STEP;
    }

    /**
     * Keeps the current buffer while the frame fits in it, and only shrinks it
     * when it is more than twice the rounded-up frame area.
     */
    private static boolean needsNewBuffer(BufferedImage image, int width, int height) {
        if (image == null || image.getWidth() < width || image.getHeight() < height) {
            return true;
        }
        long capacity = (long) image.getWidth() * image.getHeight();
        long wanted = (long) roundUpToStep(width) * roundUpToStep(height);
        return capacity > wanted * 2;
    }

    private BufferedImage allocateBuffer(int width, int height) {
        return new BufferedImage(roundUpToStep(width), roundUpToStep(height), imageType());
    }

    private static IntBuffer asIntBuffer(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.order(ByteOrder.LITTLE_ENDIAN);
        return duplicate.asIntBuffer();
    }

    private static void copyRows(IntBuffer src, int[] dst, int stride, int width, int height,
            Rectangle[] dirtyRects) {
        if (dirtyRects == null || dirtyRects.length == 0) {
            copyRect(src, dst, stride, width, height, 0, 0, width, height);
            return;
        }
        for (Rectangle rect : dirtyRects) {
            copyRect(src, dst, stride, width, height, rect.x, rect.y, rect.width, rect.height);
        }
    }

    private static void copyRect(IntBuffer src, int[] dst, int stride, int width, int height,
            int rx, int ry, int rw, int rh) {
        int x = Math.max(0, rx);
        int y = Math.max(0, ry);
        int w = Math.min(rw - (x - rx), width - x);
        int availableRows = width <= 0 ? 0 : src.capacity() / width;
        int bottom = Math.min(Math.min(ry + rh, height), availableRows);
        if (w <= 0 || bottom <= y) {
            return;
        }
        if (x == 0 && w == width && stride == width) {
            src.position(y * width);
            src.get(dst, y * stride, (bottom - y) * width);
            return;
        }
        for (int row = y; row < bottom; row++) {
            src.position(row * width + x);
            src.get(dst, row * stride + x, w);
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

    private void repaintCanvas(Rectangle[] dirtyRects) {
        BufferedCanvas canvas = canvas_;
        if (canvas == null) {
            return;
        }
        if (dirtyRects == null || dirtyRects.length == 0) {
            canvas.repaint();
            return;
        }
        double sf = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Rectangle rect : dirtyRects) {
            if (rect.width <= 0 || rect.height <= 0) {
                continue;
            }
            minX = Math.min(minX, rect.x);
            minY = Math.min(minY, rect.y);
            maxX = Math.max(maxX, rect.x + rect.width);
            maxY = Math.max(maxY, rect.y + rect.height);
        }
        if (minX > maxX || minY > maxY) {
            canvas.repaint();
            return;
        }
        int x = (int) Math.floor(minX / sf) - 1;
        int y = (int) Math.floor(minY / sf) - 1;
        int right = (int) Math.ceil(maxX / sf) + 1;
        int bottom = (int) Math.ceil(maxY / sf) + 1;
        canvas.repaint(x, y, right - x, bottom - y);
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
            if (canvas != null) {
                canvas.rememberCursorType(cursorType);
            }
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
            g.drawImage(mainImage_, 0, 0, mainWidth_, mainHeight_, 0, 0, mainWidth_, mainHeight_,
                    null);
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
        private int lastCursorType_ = Cursor.DEFAULT_CURSOR;

        boolean isAutoScrolling() {
            return autoScroll_;
        }

        void rememberCursorType(int cursorType) {
            lastCursorType_ = cursorType;
        }

        private boolean isOverClickable() {
            return lastCursorType_ == Cursor.HAND_CURSOR;
        }

        private void setAutoScrollCursor(boolean on) {
            autoScroll_ = on;
            if (on) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                return;
            }
            try {
                setCursor(Cursor.getPredefinedCursor(lastCursorType_));
            } catch (RuntimeException ignored) {
                setCursor(Cursor.getDefaultCursor());
            }
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

            // A tab that was hidden while its resize was pending skipped the
            // verification; resume it once the component is on screen again.
            addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                        && !resizeApplied_) {
                    resizeWaitStartNanos_ = System.nanoTime();
                    scheduleResizeVerify();
                }
            });

            MouseListener mouseListener = new MouseListener() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    // Assert the render-widget focus on every press. In windowless
                    // mode the blinking caret is only shown while the widget is
                    // focused, and relying solely on the AWT focusGained event is
                    // unreliable for a lightweight component embedded in Swing.
                    CefBrowserOsrBuffered.this.setFocus(true);
                    if (e.getButton() == MouseEvent.BUTTON2) {
                        if (autoScroll_) {
                            setAutoScrollCursor(false);
                        } else if (!isOverClickable()) {
                            setAutoScrollCursor(true);
                        }
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
                    // AWT reports the Enter key as '\n' (line feed), but web forms
                    // trigger implicit submission on the '\r' (carriage return)
                    // character event, matching what a native Windows browser
                    // delivers. Remap it so Enter submits forms in OSR mode.
                    if (e.getKeyChar() == '\n') {
                        e.setKeyChar('\r');
                    }
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
            viewRect_ = new Rectangle(0, 0, width, height);
            updateScreenPoint();
            updateScaleFactor();
            requestResize();
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
            long start = PaintStats.ENABLED ? System.nanoTime() : 0L;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getBackground());
                synchronized (paintLock_) {
                    if (mainImage_ == null) {
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        return;
                    }
                    double sf = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
                    AffineTransform t = g2.getTransform();
                    boolean deviceScale = t.getShearX() == 0 && t.getShearY() == 0
                            && Math.abs(t.getScaleX() - sf) < 1e-3
                            && Math.abs(t.getScaleY() - sf) < 1e-3;
                    if (deviceScale) {
                        // The frame is already in device pixels. Blit it 1:1 with a
                        // snapped, translation-only transform: scale(sf) combined
                        // with scale(1/sf) is rarely exactly 1.0 in floating point,
                        // which sends Java2D down the slow resampling path.
                        if (sf != 1.0) {
                            g2.setTransform(AffineTransform.getTranslateInstance(
                                    Math.round(t.getTranslateX()), Math.round(t.getTranslateY())));
                        }
                        int viewW = (int) Math.ceil(getWidth() * sf);
                        int viewH = (int) Math.ceil(getHeight() * sf);
                        int imgW = mainWidth_;
                        int imgH = mainHeight_;
                        // Only clear what the frame does not cover. While a resize
                        // is waiting for its frame, extend the page's own edge color
                        // instead of flashing the component background.
                        if (imgW < viewW || imgH < viewH) {
                            g2.setColor(edgeColor());
                        }
                        if (imgW < viewW) g2.fillRect(imgW, 0, viewW - imgW, viewH);
                        if (imgH < viewH) g2.fillRect(0, imgH, Math.min(imgW, viewW), viewH - imgH);
                        g2.drawImage(mainImage_, 0, 0, imgW, imgH, 0, 0, imgW, imgH, null);
                        drawPopup(g2, sf);
                    } else {
                        // Unusual target (printing, custom transforms): fall back to
                        // resampling the frame into logical coordinates.
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        AffineTransform saved = g2.getTransform();
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g2.scale(1.0 / sf, 1.0 / sf);
                        g2.drawImage(mainImage_, 0, 0, mainWidth_, mainHeight_, 0, 0, mainWidth_,
                                mainHeight_, null);
                        drawPopup(g2, sf);
                        g2.setTransform(saved);
                    }
                }
            } finally {
                g2.dispose();
                if (PaintStats.ENABLED) {
                    PaintStats.recordPaint(System.nanoTime() - start);
                }
            }
        }

        private void drawPopup(Graphics2D g2, double sf) {
            if (popupVisible_ && popupImage_ != null && popupRect_.width > 0) {
                int px = (int) Math.round(popupRect_.x * sf);
                int py = (int) Math.round(popupRect_.y * sf);
                g2.drawImage(popupImage_, px, py, px + popupWidth_, py + popupHeight_, 0, 0,
                        popupWidth_, popupHeight_, null);
            }
        }

        private Color edgeColor() {
            if (mainData_ == null || mainWidth_ <= 0 || mainHeight_ <= 0) {
                return getBackground();
            }
            int index = (mainHeight_ - 1) * mainStride_ + (mainWidth_ - 1);
            if (index < 0 || index >= mainData_.length) {
                return getBackground();
            }
            return new Color(mainData_[index] & 0x00FFFFFF);
        }
    }

    /**
     * Opt-in frame timing ({@code -Djcef.orion.osr.stats=true}). Logs, every
     * 5 seconds, how many frames CEF delivered, how long copying them took on
     * the CEF thread and how long Swing spent painting them on the EDT.
     */
    private static final class PaintStats {
        static final boolean ENABLED = Boolean.getBoolean("jcef.orion.osr.stats");
        private static final long WINDOW_NANOS = 5_000_000_000L;

        private static long windowStart = System.nanoTime();
        private static int frames;
        private static long copyNanos;
        private static int paints;
        private static long paintNanos;
        private static long maxPaintNanos;

        static synchronized void recordCopy(long nanos) {
            frames++;
            copyNanos += nanos;
            flushIfDue();
        }

        static synchronized void recordPaint(long nanos) {
            paints++;
            paintNanos += nanos;
            maxPaintNanos = Math.max(maxPaintNanos, nanos);
            flushIfDue();
        }

        private static void flushIfDue() {
            long now = System.nanoTime();
            long elapsed = now - windowStart;
            if (elapsed < WINDOW_NANOS) {
                return;
            }
            double seconds = elapsed / 1e9;
            System.out.printf(
                    "[JCEF OSR] onPaint %.1f fps (copy avg %.2f ms) | paint %.1f/s (avg %.2f ms, max %.2f ms)%n",
                    frames / seconds, frames == 0 ? 0 : copyNanos / 1e6 / frames,
                    paints / seconds, paints == 0 ? 0 : paintNanos / 1e6 / paints,
                    maxPaintNanos / 1e6);
            windowStart = now;
            frames = 0;
            copyNanos = 0;
            paints = 0;
            paintNanos = 0;
            maxPaintNanos = 0;
        }
    }
}
