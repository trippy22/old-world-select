# Old World Select

Brings back the old world select on the login screen: one black screen with a
full-width grid of worlds, the World / Players / Location / Type sort arrows and
a Cancel button, without the side menu of filters.

![The old world select on the login screen](docs/old-world-select.webp)

## Using it

Click the world button in the bottom-left of the login screen as usual.

- Click a world to switch to it.
- Click the red/green arrows in the header to sort.
- If there are more worlds than fit, use the arrows at the left and right edges.
- Hover a world to see its activity.
- Click Cancel or press Escape to go back.

The plugin only acts on the login screen. It works with or without a GPU plugin,
and with stretched mode.

## How it draws

RuneLite overlays only render in game, so the world select is a borderless
window laid exactly over the game canvas while it is open. It follows the client
when it moves or resizes, and never takes keyboard focus. Nothing in the
client's rendering is touched.

World data comes from RuneLite's own world service; the plugin makes no network
requests of its own.
