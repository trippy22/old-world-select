package com.oldworldselect;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.Scene;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.http.api.worlds.WorldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings back the old logged-out world select: one black screen, a grid of world buttons,
 * four sort headers and a Cancel button - no side menu.
 *
 * The login screen is drawn by the engine, and RuneLite overlays only render in game. With a
 * GPU plugin running, the world select is painted into the game buffer just before that
 * plugin uploads it; without one the plugin hands the finished frame to the window itself
 * while the screen is open. Nothing is hooked unless the world select is actually open.
 */
@PluginDescriptor(name = "Old World Select", internalName = "old-world-select",
    description = "Replaces the logged-out world select with the old single-grid one.",
    tags = {"world", "hopper", "select", "login", "old"})
public class OldWorldSelectPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(OldWorldSelectPlugin.class);

    // Size of the login screen the engine lays everything out in.
    private static final int SCREEN_W = 765;
    private static final int HEADER_H = 23;
    private static final int GRID_H = 480;
    // World button size (sl_back sprites).
    private static final int BUTTON_W = 88;
    private static final int BUTTON_H = 19;
    // "Click to switch" world button on the login screen, relative to the 765px area.
    private static final int SWITCH_X = 5;
    private static final int SWITCH_Y = 463;
    private static final int SWITCH_W = 100;
    private static final int SWITCH_H = 35;

    private static final int SORT_WORLD = 0;
    private static final int SORT_PLAYERS = 1;
    private static final int SORT_LOCATION = 2;
    private static final int SORT_TYPE = 3;
    private static final int[] SORT_X = {280, 390, 500, 610};
    private static final String[] SORT_LABEL = {"World", "Players", "Location", "Type"};

    private static final class Sprite
    {
        final BufferedImage image;
        final BufferedImage hover;
        final int offsetX;
        final int offsetY;

        Sprite(SpritePixels pixels)
        {
            image = pixels.toBufferedImage();
            offsetX = pixels.getOffsetX();
            offsetY = pixels.getOffsetY();
            // The engine draws a hovered button blended half-way to white.
            hover = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++)
            {
                for (int x = 0; x < image.getWidth(); x++)
                {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) == 0)
                    {
                        continue;
                    }
                    int r = (((argb >> 16) & 0xff) + 0xff) >> 1;
                    int g = (((argb >> 8) & 0xff) + 0xff) >> 1;
                    int b = ((argb & 0xff) + 0xff) >> 1;
                    hover.setRGB(x, y, 0xff000000 | r << 16 | g << 8 | b);
                }
            }
        }

        void draw(Graphics2D g, int x, int y, boolean hovered)
        {
            g.drawImage(hovered ? hover : image, x + offsetX, y + offsetY, null);
        }

        int width()
        {
            return image.getWidth();
        }

        int height()
        {
            return image.getHeight();
        }
    }

    /** Where the grid was last drawn, so clicks are tested against what is on screen. */
    private static final class Layout
    {
        int canvasW;
        int canvasH;
        int pad;
        int pages;
        int hovered = -1;
        int leftW;
        int rightW;
    }

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private WorldService worldService;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private DrawManager drawManager;

    private final Runnable frameListener = this::onFrame;
    // Only touched on the client thread.
    private PassThroughDrawCallbacks wrapper;
    // GPU off only: while the world select is open the plugin hands frames to the window
    // itself. presenterBuffer is the game buffer in use when that started.
    private FramePresenter presenter;
    private BufferProvider presenterBuffer;

    private volatile boolean onLoginScreen;
    private volatile boolean open;
    private volatile Layout layout = new Layout();
    private volatile int page;
    // Primary and secondary sort, as the old client kept them.
    private final int[] sortBy = {SORT_WORLD, SORT_PLAYERS};
    private final boolean[] sortDesc = {false, false};
    private volatile boolean resort;

    private WorldResult lastResult;
    private List<World> worlds = new ArrayList<>();

    private Sprite[] backs;
    private Sprite[] flags;
    private Sprite[] arrows;
    private Sprite[] stars;
    private Sprite leftArrow;
    private Sprite rightArrow;
    private boolean spritesLoaded;

    private final MouseAdapter mouseListener = new MouseAdapter()
    {
        @Override
        public MouseEvent mousePressed(MouseEvent e)
        {
            if (!onLoginScreen)
            {
                return e;
            }
            if (open)
            {
                if (SwingUtilities.isLeftMouseButton(e))
                {
                    click(e.getX(), e.getY());
                }
                e.consume();
                return e;
            }
            int pad = (client.getCanvasWidth() - SCREEN_W) / 2;
            int x = e.getX() - pad;
            if (SwingUtilities.isLeftMouseButton(e)
                && x >= SWITCH_X && x <= SWITCH_X + SWITCH_W
                && e.getY() >= SWITCH_Y && e.getY() <= SWITCH_Y + SWITCH_H)
            {
                openSelect();
                e.consume();
            }
            return e;
        }

        @Override
        public MouseEvent mouseReleased(MouseEvent e)
        {
            return swallow(e);
        }

        @Override
        public MouseEvent mouseClicked(MouseEvent e)
        {
            return swallow(e);
        }

        @Override
        public MouseEvent mouseDragged(MouseEvent e)
        {
            return swallow(e);
        }

        private MouseEvent swallow(MouseEvent e)
        {
            if (onLoginScreen && open)
            {
                e.consume();
            }
            return e;
        }
    };

    private final KeyListener keyListener = new KeyListener()
    {
        @Override
        public void keyTyped(KeyEvent e)
        {
            if (onLoginScreen && open)
            {
                e.consume();
            }
        }

        @Override
        public void keyPressed(KeyEvent e)
        {
            if (onLoginScreen && open)
            {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
                {
                    open = false;
                }
                e.consume();
            }
        }

        @Override
        public void keyReleased(KeyEvent e)
        {
            if (onLoginScreen && open)
            {
                e.consume();
            }
        }
    };

    @Override
    protected void startUp()
    {
        mouseManager.registerMouseListener(0, mouseListener);
        keyManager.registerKeyListener(keyListener);
        drawManager.registerEveryFrameListener(frameListener);
        clientThread.invoke(() -> stateChanged(client.getGameState()));
    }

    @Override
    protected void shutDown()
    {
        mouseManager.unregisterMouseListener(mouseListener);
        keyManager.unregisterKeyListener(keyListener);
        drawManager.unregisterEveryFrameListener(frameListener);
        open = false;
        onLoginScreen = false;
        clientThread.invoke(() ->
        {
            unhook();
            backs = null;
            flags = null;
            arrows = null;
            stars = null;
            leftArrow = null;
            rightArrow = null;
            spritesLoaded = false;
            worlds = new ArrayList<>();
            lastResult = null;
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        stateChanged(event.getGameState());
    }

    private void stateChanged(GameState state)
    {
        boolean login = state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR;
        onLoginScreen = login;
        if (!login)
        {
            open = false;
            unhook();
        }
    }

    // ---- drawing hooks ----

    /**
     * Runs on the client thread at the end of every frame, with or without the GPU plugin.
     * Nothing is hooked unless the world select is open, and everything is put back the
     * frame after it closes.
     *
     * With a GPU plugin: its draw callbacks are wrapped in a pass-through.
     *
     * Without one: the client normally puts its frame on the window before any plugin can
     * add to it, which is what made drawing afterwards flicker. So for as long as the world
     * select is open the client is told that frames are handed over by draw callbacks (the
     * same switch the GPU plugin uses), and {@link FramePresenter} puts the finished frame,
     * world select included, on the window in one go.
     */
    private void onFrame()
    {
        if (!open || !onLoginScreen)
        {
            unhook();
            return;
        }
        DrawCallbacks current = client.getDrawCallbacks();
        if (current != null && (current == wrapper || current == presenter))
        {
            return;
        }
        // Whatever we had installed has been replaced or removed by something else.
        wrapper = null;
        presenter = null;
        presenterBuffer = null;
        if (current == null)
        {
            presenter = new FramePresenter();
            presenterBuffer = client.getBufferProvider();
            client.setDrawCallbacks(presenter);
            client.setGpuFlags(DrawCallbacks.GPU);
        }
        else
        {
            wrapper = new PassThroughDrawCallbacks(current, this::paintBuffer);
            client.setDrawCallbacks(wrapper);
        }
    }

    private void unhook()
    {
        if (wrapper != null)
        {
            // Only put the original back if nothing else replaced ours in the meantime.
            if (client.getDrawCallbacks() == wrapper)
            {
                client.setDrawCallbacks(wrapper.delegate);
            }
            wrapper = null;
        }
        if (presenter != null)
        {
            if (client.getDrawCallbacks() == presenter)
            {
                client.setDrawCallbacks(null);
                client.setGpuFlags(0);
                if (client.getBufferProvider() != presenterBuffer)
                {
                    // The window was resized while we were presenting, so the game buffer
                    // was rebuilt in GPU form; have the client rebuild it for normal drawing.
                    client.resizeCanvas();
                }
            }
            presenter = null;
            presenterBuffer = null;
        }
    }

    /** GPU off: puts each finished frame on the window while the world select is open. */
    private final class FramePresenter implements DrawCallbacks
    {
        @Override
        public void draw(int overlayColor)
        {
            paintBuffer();
            Image shown = null;
            try
            {
                shown = presentFrame();
            }
            catch (RuntimeException ex)
            {
                log.warn("Old World Select: presenting the frame failed", ex);
                open = false;
            }
            // The client skips this while draw callbacks present the frame. It keeps
            // screenshots working and runs onFrame, which unhooks once the select closes.
            final Image frame = shown;
            drawManager.processDrawComplete(() -> copyOf(frame));
        }

        @Override
        public void swapScene(Scene scene)
        {
        }
    }

    private Image presentFrame()
    {
        BufferProvider buffer = client.getBufferProvider();
        Canvas canvas = client.getCanvas();
        if (!(buffer instanceof MainBufferProvider) || canvas == null)
        {
            return null;
        }
        Image image = ((MainBufferProvider) buffer).getImage();
        Graphics cg = image == null ? null : canvas.getGraphics();
        if (cg == null)
        {
            return image;
        }
        try
        {
            if (client.isStretchedEnabled())
            {
                Dimension stretched = client.getStretchedDimensions();
                if (cg instanceof Graphics2D)
                {
                    ((Graphics2D) cg).setRenderingHint(RenderingHints.KEY_INTERPOLATION, client.isStretchedFast()
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                }
                cg.drawImage(image, 0, 0, stretched.width, stretched.height, null);
            }
            else
            {
                cg.drawImage(image, 0, 0, null);
            }
        }
        finally
        {
            cg.dispose();
        }
        return image;
    }

    private static Image copyOf(Image image)
    {
        if (image == null)
        {
            return null;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics g = copy.getGraphics();
        try
        {
            g.drawImage(image, 0, 0, null);
        }
        finally
        {
            g.dispose();
        }
        return copy;
    }

    // ---- state ----

    private void openSelect()
    {
        page = 0;
        resort = true;
        open = true;
        // Fresh player counts; the list already in memory is shown until this returns.
        executor.execute(() ->
        {
            try
            {
                worldService.refresh();
            }
            catch (RuntimeException ex)
            {
                log.debug("World list refresh failed", ex);
            }
        });
    }

    private void click(int x, int y)
    {
        Layout l = layout;
        int rx = x - l.pad;
        if (y >= 4 && y <= 18)
        {
            for (int i = 0; i < SORT_X.length; i++)
            {
                if (rx >= SORT_X[i] && rx <= SORT_X[i] + 14)
                {
                    sort(i, false);
                    return;
                }
                if (rx >= SORT_X[i] + 15 && rx <= SORT_X[i] + 80)
                {
                    sort(i, true);
                    return;
                }
            }
        }
        if (rx >= 708 && rx <= 758 && y >= 4 && y <= 20)
        {
            open = false;
            return;
        }
        if (l.hovered != -1)
        {
            final int index = l.hovered;
            open = false;
            clientThread.invoke(() -> changeWorld(index));
            return;
        }
        if (y >= l.canvasH / 2 - 50 && y <= l.canvasH / 2 + 50)
        {
            if (page > 0 && x >= 0 && x <= l.leftW)
            {
                page--;
            }
            else if (page < l.pages && x >= l.canvasW - l.rightW - 5 && x <= l.canvasW)
            {
                page++;
            }
        }
    }

    private synchronized void sort(int option, boolean desc)
    {
        if (sortBy[0] != option)
        {
            sortBy[1] = sortBy[0];
            sortDesc[1] = sortDesc[0];
        }
        sortBy[0] = option;
        sortDesc[0] = desc;
        resort = true;
    }

    private void changeWorld(int index)
    {
        if (index < 0 || index >= worlds.size())
        {
            return;
        }
        GameState state = client.getGameState();
        if (state != GameState.LOGIN_SCREEN && state != GameState.LOGIN_SCREEN_AUTHENTICATOR)
        {
            return;
        }
        World world = worlds.get(index);
        net.runelite.api.World rsWorld = client.createWorld();
        rsWorld.setActivity(world.getActivity());
        rsWorld.setAddress(world.getAddress());
        rsWorld.setId(world.getId());
        rsWorld.setPlayerCount(world.getPlayers());
        rsWorld.setLocation(world.getRegion() == null ? 0 : world.getRegion().getId());
        rsWorld.setTypes(WorldUtil.toWorldTypes(world.getTypes()));
        client.changeWorld(rsWorld);
    }

    private void updateWorlds()
    {
        WorldResult result = worldService.getWorlds();
        if (result == lastResult && !resort)
        {
            return;
        }
        lastResult = result;
        resort = false;
        List<World> sorted = new ArrayList<>();
        if (result != null && result.getWorlds() != null)
        {
            sorted.addAll(result.getWorlds());
        }
        final int by0;
        final int by1;
        final boolean desc0;
        final boolean desc1;
        synchronized (this)
        {
            by0 = sortBy[0];
            by1 = sortBy[1];
            desc0 = sortDesc[0];
            desc1 = sortDesc[1];
        }
        Comparator<World> order = (a, b) ->
        {
            int c = compare(a, b, by0, desc0);
            if (c == 0)
            {
                c = compare(a, b, by1, desc1);
            }
            return c != 0 ? c : Integer.compare(a.getId(), b.getId());
        };
        sorted.sort(order);
        worlds = sorted;
    }

    private static int compare(World a, World b, int option, boolean desc)
    {
        int c;
        switch (option)
        {
            case SORT_PLAYERS:
                c = Integer.compare(a.getPlayers(), b.getPlayers());
                break;
            case SORT_LOCATION:
                c = Integer.compare(location(a), location(b));
                break;
            case SORT_TYPE:
                String x = a.getActivity() == null ? "-" : a.getActivity();
                String y = b.getActivity() == null ? "-" : b.getActivity();
                boolean xNone = x.equals("-");
                boolean yNone = y.equals("-");
                if (xNone || yNone)
                {
                    // Worlds with no activity always sink to the end.
                    return xNone == yNone ? 0 : xNone ? 1 : -1;
                }
                c = x.compareTo(y);
                break;
            default:
                c = Integer.compare(a.getId(), b.getId());
        }
        return desc ? -c : c;
    }

    private static int location(World w)
    {
        return w.getRegion() == null ? 0 : w.getRegion().getId();
    }

    // ---- drawing ----

    private void loadSprites()
    {
        if (spritesLoaded)
        {
            return;
        }
        backs = load(SpriteID.SL_BACK);
        flags = load(SpriteID.SL_FLAGS);
        arrows = load(SpriteID.SL_ARROWS);
        stars = load(SpriteID.SL_STARS);
        Sprite[] left = load(SpriteID.LEFTARROW);
        Sprite[] right = load(SpriteID.RIGHTARROW);
        leftArrow = left == null ? null : left[0];
        rightArrow = right == null ? null : right[0];
        spritesLoaded = backs != null && flags != null && arrows != null && stars != null;
    }

    private Sprite[] load(int spriteId)
    {
        SpritePixels[] pixels = client.getSprites(client.getIndexSprites(), spriteId, 0);
        if (pixels == null || pixels.length == 0)
        {
            return null;
        }
        Sprite[] out = new Sprite[pixels.length];
        for (int i = 0; i < pixels.length; i++)
        {
            out[i] = new Sprite(pixels[i]);
        }
        return out;
    }

    /** Paints the world select into the game buffer, just before the frame is handed over. */
    private void paintBuffer()
    {
        if (!open)
        {
            return;
        }
        try
        {
            BufferProvider buffer = client.getBufferProvider();
            if (!(buffer instanceof MainBufferProvider))
            {
                return;
            }
            Image image = ((MainBufferProvider) buffer).getImage();
            if (image == null)
            {
                return;
            }
            loadSprites();
            updateWorlds();
            Graphics2D g = (Graphics2D) image.getGraphics();
            try
            {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                paint(g, buffer.getWidth(), buffer.getHeight());
            }
            finally
            {
                g.dispose();
            }
        }
        catch (RuntimeException ex)
        {
            log.warn("Old World Select: drawing failed", ex);
            open = false;
        }
    }

    private void paint(Graphics2D g, int canvasW, int canvasH)
    {
        final Font bold = FontManager.getRunescapeBoldFont();
        final Font small = FontManager.getRunescapeSmallFont();
        final int pad = (canvasW - SCREEN_W) / 2;
        final net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        final int mouseX = mouse == null ? -1 : mouse.getX();
        final int mouseY = mouse == null ? -1 : mouse.getY();

        Layout l = new Layout();
        l.canvasW = canvasW;
        l.canvasH = canvasH;
        l.pad = pad;

        // Header bar and black body.
        g.setColor(Color.BLACK);
        g.fillRect(pad, HEADER_H, SCREEN_W, GRID_H);
        g.setPaint(new GradientPaint(0, 0, new Color(0xBD9839), 0, HEADER_H, new Color(0x8B6608)));
        g.fillRect(pad, 0, 125, HEADER_H);
        g.setPaint(new GradientPaint(0, 0, new Color(0x4F4F4F), 0, HEADER_H, new Color(0x292929)));
        g.fillRect(pad + 125, 0, 640, HEADER_H);

        centered(g, bold, "Select a world", pad + 62, 15, Color.BLACK);

        if (stars != null && stars.length >= 2)
        {
            stars[1].draw(g, pad + 140, 1, false);
            text(g, small, "Members only world", pad + 152, 10, Color.WHITE);
            stars[0].draw(g, pad + 140, 12, false);
            text(g, small, "Free world", pad + 152, 21, Color.WHITE);
        }

        if (arrows != null && arrows.length >= 4)
        {
            final int by;
            final boolean desc;
            synchronized (this)
            {
                by = sortBy[0];
                desc = sortDesc[0];
            }
            for (int i = 0; i < SORT_X.length; i++)
            {
                int x = pad + SORT_X[i];
                arrows[by == i && !desc ? 2 : 0].draw(g, x, 4, false);
                arrows[by == i && desc ? 3 : 1].draw(g, x + 15, 4, false);
                text(g, bold, SORT_LABEL[i], x + 32, 17, Color.WHITE);
            }
        }

        g.setColor(Color.BLACK);
        g.fillRect(pad + 708, 4, 50, 16);
        centered(g, small, "Cancel", pad + 708 + 25, 16, Color.WHITE);

        final List<World> list = worlds;
        final int count = list.size();
        if (backs == null || count == 0)
        {
            centered(g, bold, "Loading world list...", pad + SCREEN_W / 2, HEADER_H + GRID_H / 2, Color.WHITE);
            layout = l;
            return;
        }

        // Same fitting rule as the engine: start from 7 x 24 and shrink while the list still fits.
        int cols = SCREEN_W / (BUTTON_W + 1) - 1;
        int rows = GRID_H / (BUTTON_H + 1);
        int prevCols;
        int prevRows;
        do
        {
            prevRows = rows;
            prevCols = cols;
            if (rows * (cols - 1) >= count)
            {
                cols--;
            }
            if (cols * (rows - 1) >= count)
            {
                rows--;
            }
            if (cols * (rows - 1) >= count)
            {
                rows--;
            }
        }
        while (prevRows != rows || prevCols != cols);

        int gapX = Math.min(5, (SCREEN_W - cols * BUTTON_W) / (cols + 1));
        int gapY = Math.min(5, (GRID_H - rows * BUTTON_H) / (rows + 1));
        int startX = (SCREEN_W - cols * BUTTON_W - gapX * (cols - 1)) / 2;
        int startY = (GRID_H - rows * BUTTON_H - gapY * (rows - 1)) / 2;
        int totalCols = (rows + count - 1) / rows;
        l.pages = Math.max(0, totalCols - cols);
        if (page > l.pages)
        {
            page = l.pages;
        }
        final int currentPage = page;

        if (leftArrow != null && currentPage > 0)
        {
            leftArrow.draw(g, 8, canvasH / 2 - leftArrow.height() / 2, false);
        }
        if (rightArrow != null && currentPage < l.pages)
        {
            rightArrow.draw(g, canvasW - rightArrow.width() - 8, canvasH / 2 - rightArrow.height() / 2, false);
        }
        l.leftW = leftArrow == null ? 0 : leftArrow.width();
        l.rightW = rightArrow == null ? 0 : rightArrow.width();

        int y = startY + HEADER_H;
        int x = startX + pad;
        int row = 0;
        int col = currentPage;
        for (int i = rows * currentPage; i < count && col - currentPage < cols; i++)
        {
            World world = list.get(i);
            EnumSet<WorldType> types = world.getTypes();
            boolean members = types.contains(WorldType.MEMBERS);
            boolean selectable = true;
            String players = Integer.toString(world.getPlayers());
            if (world.getPlayers() == -1)
            {
                players = "OFF";
                selectable = false;
            }
            else if (world.getPlayers() > 1980)
            {
                players = "FULL";
                selectable = false;
            }

            Color idColor = Color.BLACK;
            int back;
            // sl_back comes in free/members pairs: 0 normal, 2 PvP, 4 deadman, 6 no-save,
            // 8 seasonal, 10 speedrunning, 12 fresh start, 14 beta, 16 high risk.
            if (types.contains(WorldType.DEADMAN))
            {
                idColor = Color.RED;
                back = 4;
            }
            else if (types.contains(WorldType.FRESH_START_WORLD))
            {
                back = 12;
            }
            else if (types.contains(WorldType.BETA_WORLD))
            {
                back = 14;
            }
            else if (types.contains(WorldType.NOSAVE_MODE))
            {
                back = 6;
            }
            else if (types.contains(WorldType.QUEST_SPEEDRUNNING))
            {
                back = 10;
            }
            else if (types.contains(WorldType.SEASONAL) || types.contains(WorldType.TOURNAMENT))
            {
                back = 8;
            }
            else if (types.contains(WorldType.HIGH_RISK))
            {
                back = 16;
            }
            else if (types.contains(WorldType.PVP))
            {
                back = 2;
            }
            else
            {
                back = 0;
            }
            if (members)
            {
                back++;
            }
            if (back >= backs.length)
            {
                back = members ? 1 : 0;
            }

            boolean hover = selectable && mouseX >= x && mouseY >= y && mouseX < x + BUTTON_W && mouseY < y + BUTTON_H;
            if (hover)
            {
                l.hovered = i;
            }
            backs[back].draw(g, x, y, hover);

            if (flags != null)
            {
                int flag = flagIndex(world, members);
                if (flag >= 0 && flag < flags.length)
                {
                    flags[flag].draw(g, x + 29, y, false);
                }
            }

            centered(g, bold, Integer.toString(world.getId()), x + 15, y + BUTTON_H / 2 + 5, idColor);
            centered(g, small, players, x + 60, y + BUTTON_H / 2 + 5, Color.WHITE);

            y += gapY + BUTTON_H;
            if (++row >= rows)
            {
                y = startY + HEADER_H;
                x += gapX + BUTTON_W;
                row = 0;
                col++;
            }
        }

        if (l.hovered != -1)
        {
            String activity = list.get(l.hovered).getActivity();
            if (activity == null)
            {
                activity = "-";
            }
            FontMetrics fm = g.getFontMetrics(small);
            int w = fm.stringWidth(activity) + 6;
            int h = fm.getAscent() + 8;
            int ty = mouseY + 25;
            if (ty + h > 480)
            {
                ty = mouseY - 25 - h;
            }
            g.setColor(new Color(0xFFFFA0));
            g.fillRect(mouseX - w / 2, ty, w, h);
            g.setColor(Color.BLACK);
            g.drawRect(mouseX - w / 2, ty, w - 1, h - 1);
            centered(g, small, activity, mouseX, ty + fm.getAscent() + 4, Color.BLACK);
        }

        layout = l;
    }

    private int flagIndex(World world, boolean members)
    {
        return (members ? flags.length / 2 : 0) + location(world);
    }

    private static void text(Graphics2D g, Font font, String s, int x, int y, Color color)
    {
        g.setFont(font);
        g.setColor(color);
        g.drawString(s, x, y);
    }

    private static void centered(Graphics2D g, Font font, String s, int x, int y, Color color)
    {
        g.setFont(font);
        g.setColor(color);
        g.drawString(s, x - g.getFontMetrics().stringWidth(s) / 2, y);
    }
}
