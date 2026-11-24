package com.oldworldselect;

import java.util.Set;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Forwards every call to the GPU plugin's own draw callbacks unchanged. The only addition
 * is {@code beforeFrame}, run just before the finished frame is handed over, which is when
 * the world select is painted into the game buffer.
 *
 * It is only installed while the world select is open on the login screen, so none of the
 * scene methods are expected to be called through it; they are forwarded anyway.
 */
final class PassThroughDrawCallbacks implements DrawCallbacks
{
    final DrawCallbacks delegate;
    private final Runnable beforeFrame;

    PassThroughDrawCallbacks(DrawCallbacks delegate, Runnable beforeFrame)
    {
        this.delegate = delegate;
        this.beforeFrame = beforeFrame;
    }

    @Override
    public void draw(int overlayColor)
    {
        beforeFrame.run();
        delegate.draw(overlayColor);
    }

    @Override
    public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
    {
        delegate.draw(projection, scene, renderable, orientation, x, y, z, hash);
    }

    @Override
    public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY)
    {
        delegate.drawScenePaint(scene, paint, plane, tileX, tileY);
    }

    @Override
    public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY)
    {
        delegate.drawSceneTileModel(scene, model, tileX, tileY);
    }

    @Override
    public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
    {
        delegate.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
    }

    @Override
    public void postDrawScene()
    {
        delegate.postDrawScene();
    }

    @Override
    public void animate(Texture texture, int diff)
    {
        delegate.animate(texture, diff);
    }

    @Override
    public void loadScene(Scene scene)
    {
        delegate.loadScene(scene);
    }

    @Override
    public void swapScene(Scene scene)
    {
        delegate.swapScene(scene);
    }

    @Override
    public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos,
        int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
    {
        return delegate.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
    }

    @Override
    public boolean zoneInFrustum(int zx, int zz, int maxY, int minY)
    {
        return delegate.zoneInFrustum(zx, zz, maxY, minY);
    }

    @Override
    public void loadScene(WorldView worldView, Scene scene)
    {
        delegate.loadScene(worldView, scene);
    }

    @Override
    public void despawnWorldView(WorldView worldView)
    {
        delegate.despawnWorldView(worldView);
    }

    @Override
    public void preSceneDraw(Scene scene, Projection projection, float cameraX, float cameraY, float cameraZ,
        float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
    {
        delegate.preSceneDraw(scene, projection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel, hideRoofIds);
    }

    @Override
    public void preSceneDraw(Scene scene, float cameraX, float cameraY, float cameraZ,
        float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
    {
        delegate.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel, hideRoofIds);
    }

    @Override
    public void postSceneDraw(Scene scene)
    {
        delegate.postSceneDraw(scene);
    }

    @Override
    public void drawPass(Projection projection, Scene scene, int pass)
    {
        delegate.drawPass(projection, scene, pass);
    }

    @Override
    public void drawZoneOpaque(Projection projection, Scene scene, int zx, int zz)
    {
        delegate.drawZoneOpaque(projection, scene, zx, zz);
    }

    @Override
    public void drawZoneAlpha(Projection projection, Scene scene, int level, int zx, int zz)
    {
        delegate.drawZoneAlpha(projection, scene, level, zx, zz);
    }

    @Override
    public void drawDynamic(Projection projection, Scene scene, TileObject tileObject, Renderable renderable, Model model,
        int orientation, int x, int y, int z)
    {
        delegate.drawDynamic(projection, scene, tileObject, renderable, model, orientation, x, y, z);
    }

    @Override
    public void drawDynamic(int renderThreadId, Projection projection, Scene scene, TileObject tileObject, Renderable renderable,
        Model model, int orientation, int x, int y, int z)
    {
        delegate.drawDynamic(renderThreadId, projection, scene, tileObject, renderable, model, orientation, x, y, z);
    }

    @Override
    public void drawTemp(Projection projection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
    {
        delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
    }

    @Override
    public void invalidateZone(Scene scene, int zx, int zz)
    {
        delegate.invalidateZone(scene, zx, zz);
    }
}
