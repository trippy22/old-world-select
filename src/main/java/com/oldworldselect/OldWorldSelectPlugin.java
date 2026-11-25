package com.oldworldselect;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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
 * The login screen is drawn by the engine, and RuneLite overlays only render in game, so the
 * world select is a borderless window laid exactly over the game canvas. Nothing in the
 * client's rendering is touched.
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
    // World button in the bottom-left corner of the login screen, relative to the 765px area.
    private static final int SWITCH_X = 4;
    private static final int SWITCH_Y = 450;
    private static final int SWITCH_W = 126;
    private static final int SWITCH_H = 53;

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

    private volatile boolean onLoginScreen;
    private volatile boolean open;

    // Everything below is only touched on the Swing thread.
    private JWindow window;
    private Timer timer;
    private int mouseX = -1;
    private int mouseY = -1;
    private Layout layout = new Layout();
    private int page;
    // Primary and secondary sort, as the old client kept them.
    private final int[] sortBy = {SORT_WORLD, SORT_PLAYERS};
    private final boolean[] sortDesc = {false, false};
    private boolean resort;

    private WorldResult lastResult;
    private List<World> worlds = new ArrayList<>();

    private Sprite[] backs;
    private Sprite[] flags;
    private Sprite[] arrows;
    private Sprite[] stars;
    private Sprite leftArrow;
    private Sprite rightArrow;

    // Sees clicks on the login screen itself; while the world select is up, its window is in
    // front of the canvas and gets the mouse instead.
    private final MouseAdapter mouseListener = new MouseAdapter()
    {
        // The world button press we consumed; its release and click are consumed too.
        private boolean swallowRelease;

        @Override
        public MouseEvent mousePressed(MouseEvent e)
        {
            int x = e.getX() - (client.getCanvasWidth() - SCREEN_W) / 2;
            swallowRelease = onLoginScreen && SwingUtilities.isLeftMouseButton(e)
                && x >= SWITCH_X && x <= SWITCH_X + SWITCH_W
                && e.getY() >= SWITCH_Y && e.getY() <= SWITCH_Y + SWITCH_H;
            if (swallowRelease)
            {
                openSelect();
                e.consume();
            }
            return e;
        }

        @Override
        public MouseEvent mouseReleased(MouseEvent e)
        {
            if (swallowRelease)
            {
                e.consume();
            }
            return e;
        }

        @Override
        public MouseEvent mouseClicked(MouseEvent e)
        {
            if (swallowRelease)
            {
                swallowRelease = false;
                e.consume();
            }
            return e;
        }
    };

    // The window never takes keyboard focus, so keys still arrive at the client. They are
    // kept away from the login form while the world select is up, and Escape closes it.
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
                    SwingUtilities.invokeLater(OldWorldSelectPlugin.this::close);
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
        // Appended, not put first: Stretched Mode registers its coordinate translation at the
        // front, and this has to run after it to see clicks in game coordinates.
        mouseManager.registerMouseListener(mouseListener);
        keyManager.registerKeyListener(keyListener);
        clientThread.invoke(() -> stateChanged(client.getGameState()));
    }

    @Override
    protected void shutDown()
    {
        mouseManager.unregisterMouseListener(mouseListener);
        keyManager.unregisterKeyListener(keyListener);
        onLoginScreen = false;
        SwingUtilities.invokeLater(() ->
        {
            close();
            backs = null;
            flags = null;
            arrows = null;
            stars = null;
            leftArrow = null;
            rightArrow = null;
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
        // The window closes itself on its next tick once this goes false.
        onLoginScreen = state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR;
    }

    // ---- window ----

    private void openSelect()
    {
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
        // Sprites have to be read on the client thread; the window belongs to the Swing thread.
        clientThread.invoke(() ->
        {
            final Sprite[][] loaded = {
                load(SpriteID.SL_BACK), load(SpriteID.SL_FLAGS), load(SpriteID.SL_ARROWS),
                load(SpriteID.SL_STARS), load(SpriteID.LEFTARROW), load(SpriteID.RIGHTARROW)};
            SwingUtilities.invokeLater(() -> show(loaded));
        });
    }

    private void show(Sprite[][] loaded)
    {
        close();
        Canvas canvas = client.getCanvas();
        if (!onLoginScreen || canvas == null || !canvas.isShowing())
        {
            return;
        }
        backs = loaded[0];
        flags = loaded[1];
        arrows = loaded[2];
        stars = loaded[3];
        leftArrow = loaded[4] == null ? null : loaded[4][0];
        rightArrow = loaded[5] == null ? null : loaded[5][0];
        page = 0;
        resort = true;
        mouseX = -1;
        mouseY = -1;

        window = new JWindow(SwingUtilities.getWindowAncestor(canvas));
        window.setFocusableWindowState(false);
        window.setContentPane(new SelectPanel());
        window.setBounds(new Rectangle(canvas.getLocationOnScreen(), canvas.getSize()));
        window.setVisible(true);
        open = true;
        // Keeps the window over the canvas as the client moves or resizes, and the player
        // counts current.
        timer = new Timer(50, e -> tick());
        timer.start();
    }

    private void tick()
    {
        Canvas canvas = client.getCanvas();
        if (!onLoginScreen || canvas == null || !canvas.isShowing())
        {
            close();
            return;
        }
        Rectangle bounds = new Rectangle(canvas.getLocationOnScreen(), canvas.getSize());
        if (!bounds.equals(window.getBounds()))
        {
            window.setBounds(bounds);
        }
        window.repaint();
    }

    private void close()
    {
        open = false;
        if (timer != null)
        {
            timer.stop();
            timer = null;
        }
        if (window != null)
        {
            window.dispose();
            window = null;
        }
    }

    /** The world select itself, laid out in game pixels and scaled to the canvas. */
    private final class SelectPanel extends JComponent
    {
        SelectPanel()
        {
            java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter()
            {
                @Override
                public void mouseMoved(MouseEvent e)
                {
                    mouseX = e.getX() * gameWidth() / Math.max(1, getWidth());
                    mouseY = e.getY() * gameHeight() / Math.max(1, getHeight());
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e)
                {
                    mouseMoved(e);
                }

                @Override
                public void mouseExited(MouseEvent e)
                {
                    mouseX = -1;
                    mouseY = -1;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e)
                {
                    mouseMoved(e);
                    if (SwingUtilities.isLeftMouseButton(e))
                    {
                        click(mouseX, mouseY);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        // With stretched mode on the canvas is bigger than the game; these are game pixels.
        private int gameWidth()
        {
            int w = client.getCanvasWidth();
            return w > 0 ? w : Math.max(1, getWidth());
        }

        private int gameHeight()
        {
            int h = client.getCanvasHeight();
            return h > 0 ? h : Math.max(1, getHeight());
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            Graphics2D g = (Graphics2D) graphics.create();
            try
            {
                int w = gameWidth();
                int h = gameHeight();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.scale(getWidth() / (double) w, getHeight() / (double) h);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                updateWorlds();
                paintSelect(g, w, h);
            }
            finally
            {
                g.dispose();
            }
        }
    }

    // ---- state ----

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
            close();
            return;
        }
        if (l.hovered >= 0 && l.hovered < worlds.size())
        {
            final World world = worlds.get(l.hovered);
            close();
            clientThread.invoke(() -> changeWorld(world));
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

    private void sort(int option, boolean desc)
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

    private void changeWorld(World world)
    {
        GameState state = client.getGameState();
        if (state != GameState.LOGIN_SCREEN && state != GameState.LOGIN_SCREEN_AUTHENTICATOR)
        {
            return;
        }
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
        Comparator<World> order = (a, b) ->
        {
            int c = compare(a, b, sortBy[0], sortDesc[0]);
            if (c == 0)
            {
                c = compare(a, b, sortBy[1], sortDesc[1]);
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

    private void paintSelect(Graphics2D g, int canvasW, int canvasH)
    {
        final Font bold = FontManager.getRunescapeBoldFont();
        final Font small = FontManager.getRunescapeSmallFont();
        final int pad = (canvasW - SCREEN_W) / 2;

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
            final int by = sortBy[0];
            final boolean desc = sortDesc[0];
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
