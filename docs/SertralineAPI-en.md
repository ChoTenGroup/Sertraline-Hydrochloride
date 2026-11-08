# SertralineAPI

`Sertraline.api()` returns a `SertralineAPI` interface instance — the unified entry point for external plugins and JS scripts to access Sertraline features. It covers item querying and building, player inventory item counting/deduction, item rebuild, the public/private item manager, the template system, and template processor registration.

All synchronous API methods that touch player inventory/data must be called from the player's thread (the main thread or the region thread).

---

## Enums

### ManagerRange

Inventory item query scope:

- `PUBLIC` — match only public items (ID does not start with `__`)
- `PRIVATE` — match only private items (ID starts with `__`)
- `BOTH` — match both public and private items

### InventorySlotRange

Inventory slot scope:

- `STORAGE` — storage area (main inventory + hotbar)
- `ALL` — all slots (additionally includes armor slots and off-hand)

### SubManagerType

Item sub-manager type:

- `TEMPORARY` — temporary (in memory, lost on restart)
- `PERSISTENT` — persistent (written to the database)

---

## Item Query & Build

### getItem

Get a Sertraline item object (`ModernSItem`); returns `null` if not registered.

`getItem(id: String): ModernSItem?`

### getAllItems

Get all registered public items as a `Map<ID, ModernSItem>`.

`getAllItems(): Map<String, ModernSItem>`

### buildItem

Build an item for a player.

`buildItem(sItem: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null): ItemStack?`

- `source`: source item; `null` builds automatically.
- `overrideData`: fields that override item data.

### buildDataItem

Build an item for a player (with variables). `vars` are template variables (persisted to NBT); `context` is the runtime context (Player, Event, etc., accessible via `{context:xxx}` tags, NOT persisted to NBT).

`buildDataItem(sItem: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null, vars: Map<String, Any?>? = null, context: Map<String, Any?>? = null): ItemStack?`

### getId

Get an item's Sertraline ID; returns `null` for non-Sertraline items.

`getId(itemStack: ItemStack): String?`

### getVal

Read a val value from a Sertraline item object (`sertraline:vals`); returns `null` if the key does not exist.

`getVal(sItem: ModernSItem, valId: String): Any?`

### getVar

Read a persisted var value from an item's NBT (`sertraline_data`); returns `null` if the key does not exist.

`getVar(itemStack: ItemStack, varId: String): Any?`

### isValidItem

Check whether an item is a Sertraline item (has a Sertraline ID).

`isValidItem(itemStack: ItemStack): Boolean`

### isRegisteredItem

Check whether an item is registered in `itemMap`.

`isRegisteredItem(itemStack: ItemStack): Boolean`

### isRegisteredItem

Check whether the given ID is registered in `itemMap`.

`isRegisteredItem(id: String): Boolean`

---

## Player Inventory Items

The following methods match items in the inventory exactly by Sertraline ID; the private scope verifies that the ID belongs to the target player's private manager. All are synchronous APIs and must be called on the player's thread.

### countItem

Count the Sertraline items in a player's inventory.

`countItem(id: String, player: Player, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### hasItem

Check whether the player has at least the given amount of the item. Non-positive `amount` always returns `false`.

`hasItem(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

### takeItem

Take as many of the item from the inventory as possible, returning the actually-taken amount. Non-positive `amount` returns `0`.

`takeItem(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### takeItemExactly

Take the full amount only when enough items are available. If the amount is insufficient or non-positive, the inventory is left unmodified and `false` is returned.

`takeItemExactly(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

### countItemAnyOwner

Count the items without verifying private-item ownership (classifies by ID only).

`countItemAnyOwner(id: String, player: Player, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### hasItemAnyOwner

Check whether the amount is sufficient without verifying private-item ownership. Non-positive `amount` returns `false`.

`hasItemAnyOwner(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

### takeItemAnyOwner

Take as many items as possible without verifying private-item ownership, returning the actually-taken amount.

`takeItemAnyOwner(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### takeItemAnyOwnerExactly

Take the full amount only when enough items are available, without verifying private-item ownership. If the amount is insufficient or non-positive, the inventory is left unmodified and `false` is returned.

`takeItemAnyOwnerExactly(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

---

## Item Rebuild

### rebuildLore

Regenerate an item's lore. **Modifies the input item in place**.

`rebuildLore(itemStack: ItemStack, player: Player?): Unit`

### rebuildName

Regenerate an item's display name. **Modifies the input item in place**.

`rebuildName(itemStack: ItemStack, player: Player?): Unit`

### rebuild

Rebuild the entire Sertraline item and return a new ItemStack. **Does NOT modify the input item**.

`rebuild(itemStack: ItemStack, player: Player?): ItemStack`

### rebuildUnsafe

Rebuild an item via ItemMeta and write into the original item. **Modifies the input item in place**. WARNING: unsafe — loses some DataComponents (3 out of 76 on 1.21.4); use only when you know the consequences.

`rebuildUnsafe(itemStack: ItemStack, player: Player?): Unit`

---

## Item Manager

### createPublicItem

Create a public item.

`createPublicItem(id: String, data: Map<String, Any?>, sub: SubManagerType = SubManagerType.TEMPORARY): Unit`

### getPublicItem

Get a public item (reads from the persistent manager by default).

`getPublicItem(id: String, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

### deletePublicItem

Delete a public item; returns whether the deletion succeeded.

`deletePublicItem(id: String, sub: SubManagerType): Boolean`

### getAllPublicItems

Get all public items of the given sub-type.

`getAllPublicItems(sub: SubManagerType): Map<String, ModernSItem>`

### createPrivateItem

Create a private item for the given UUID.

`createPrivateItem(uuid: String, id: String, data: Map<String, Any?>, sub: SubManagerType = SubManagerType.TEMPORARY): Unit`

### getPrivateItem

Get a private item for the given UUID (reads from the persistent manager by default).

`getPrivateItem(uuid: String, id: String, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

### deletePrivateItem

Delete a private item for the given UUID.

`deletePrivateItem(uuid: String, id: String, sub: SubManagerType): Unit`

### getAllPrivateItems

Get all private items of the given UUID and sub-type.

`getAllPrivateItems(uuid: String, sub: SubManagerType): Map<String, ModernSItem>`

### updateSItem (private items)

Update a private item for the given UUID. Persistent updates are also written to the database; returns the previous item, or `null` when the ID was not registered.

`updateSItem(uuid: String, id: String, item: ModernSItem, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

The item's `ModernSItem.key` can also be used as the ID:

`updateSItem(uuid: String, item: ModernSItem, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

### registerItem

Register an item directly into `itemMap` (public-persistent).

`registerItem(id: String, item: ModernSItem): Unit`

### updateSItem

Update a public item directly in `itemMap`. Returns the replaced item, or `null` when the ID was not previously registered.

`updateSItem(id: String, item: ModernSItem): ModernSItem?`

The item's `ModernSItem.key` can also be used as the ID:

`updateSItem(item: ModernSItem): ModernSItem?`

### unregisterItem

Remove an item from `itemMap`; returns the removed item (`null` if not found).

`unregisterItem(id: String): ModernSItem?`

### getItemCount

Get the total item count in `itemMap`.

`getItemCount(): Int`

### resolvePrivateUuid

Resolve a private UUID (automatic fallback: prefers the given value, falls back to the player UUID when missing).

`resolvePrivateUuid(uuid: String?, playerUuid: String?): String`

---

## Template System

### getTemplate

Get a loaded template (no parsing); returns `null` if it does not exist.

`getTemplate(name: String): Map<String, Any?>?`

### getTemplateNames

Get the names of all loaded templates.

`getTemplateNames(): Set<String>`

### getAllTemplates

Get all loaded templates (immutable snapshot).

`getAllTemplates(): Map<String, Map<String, Any?>>`

### resolveTemplate

Manually resolve a template: deep-copy → parameter substitution → recursive resolve.

`resolveTemplate(name: String, args: Map<String, String>): Map<String, Any?>?`

### getTemplateCount

Get the number of loaded templates.

`getTemplateCount(): Int`

---

## Template Processor Registration

Used to register custom `$t` transformers, `$c` converters, and argument-level directives.

### registerTransformer

Register a custom `$t` transformer type.

`registerTransformer(type: String, provider: TemplateManager.TransformerProvider): Unit`

### unregisterTransformer

Unregister a custom `$t` transformer type.

`unregisterTransformer(type: String): Unit`

### registerConverter

Register a custom `$c` converter type.

`registerConverter(type: String, provider: TemplateManager.ConverterProvider): Unit`

### unregisterConverter

Unregister a custom `$c` converter type.

`unregisterConverter(type: String): Unit`

### registerDirective

Register a custom argument-level directive (same level as `$t`/`$c`, e.g. `$myDirective`).

`registerDirective(name: String, provider: TemplateManager.DirectiveProvider): Unit`

### unregisterDirective

Unregister a custom argument-level directive.

`unregisterDirective(name: String): Unit`
