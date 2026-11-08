# ItemStackUtil

`ItemStackUtil` provides item NBT read/write and rebuild utilities, built on TabooLib's `ItemTag` and Sertraline's item rebuild pipeline.

> The vars helpers (`getVar` / `hasVar` / `setVar` / `setVarDirect` / `removeVar` / `removeVarDirect`) require plugin version **3.11.14+**.

---

## getItemTag

Get the item's `ItemTag` (the handle for reading/writing item NBT). Use `tag["key"]` to read and `tag["key"] = ...` to write.

`getItemTag(itemStack: ItemStack): ItemTag`

## setItemTag

Overwrite the item's NBT with the given `ItemTag`. **Does NOT modify the original item** — it returns the new item with the tag applied, so capture the return value.

`setItemTag(itemStack: ItemStack, tag: ItemTag): ItemStack`

## setItemTagDirect

Overwrite the item's NBT with the given `ItemTag`. **Modifies the original item in place**; the return value is the same modified item.

`setItemTagDirect(itemStack: ItemStack, tag: ItemTag): ItemStack`

## transferToByte

TabooLib does not support boolean NBT. Pass `true`/`false` through this function to convert to the corresponding `Byte` value (`1`/`0`) before writing to NBT.

`transferToByte(input: Any?): Any?`

## rebuildLore

Rebuild the item's lore from its template (recalculating variables, placeholders, conditional lines, etc.). Modifies the original item in place; no return value.

`rebuildLore(itemStack: ItemStack, player: Player?): Unit`

## rebuildName

Rebuild the item's display name from its template. Modifies the original item in place; no return value.

`rebuildName(itemStack: ItemStack, player: Player?): Unit`

## rebuild

Fully rebuild the item from its template (name + lore + attributes, etc.) and return the new item. Used to refresh old items after item data/templates change.

`rebuild(itemStack: ItemStack, player: Player?): ItemStack`

## rebuildBypassKeepData

Fast rebuild that skips the `keep-data` retention logic and rebuilds directly from the template, returning the new item. Suited for items whose source is known and that need no runtime-data retention.

`rebuildBypassKeepData(itemStack: ItemStack, player: Player?): ItemStack`

## rebuildUnsafe

Force rebuild, skipping some safety checks. Modifies the original item in place; no return value. Use only when you know exactly what you are doing.

`rebuildUnsafe(itemStack: ItemStack, player: Player?): Unit`

---

## Vars helpers

> Requires plugin version **3.11.14+**.

Convenience wrappers around the underlying `ItemTag` deep-path methods, scoped to the item's persisted vars (`sertraline_data` compound). Keys support "." nesting, e.g. `"count"` → `sertraline_data.count`, `"foo.bar"` → `sertraline_data.foo.bar`.

Compared to raw `getItemTag` + `putDeep` + `setItemTagDirect`, these handle the tag fetch/save for you and follow the same Direct / non-Direct convention as the tag helpers above:

```js
// Old way
var tag = ItemStackUtil.getItemTag(bItem);
tag.putDeep("sertraline_data.count", newValue);
ItemStackUtil.setItemTagDirect(bItem, tag);

// New way
ItemStackUtil.setVarDirect(bItem, "count", newValue);
```

## getVar

Read a var by a "."-separated key, returning `null` if it does not exist.

`getVar(itemStack: ItemStack, key: String): Any?`

## hasVar

Check whether a var exists at the key.

`hasVar(itemStack: ItemStack, key: String): Boolean`

## setVar

Write a var. **Does NOT modify the original item** — it returns the new item, so capture the return value. Passing `null` as value removes the key (also applies to nested paths).

`setVar(itemStack: ItemStack, key: String, value: Any?): ItemStack`

## setVarDirect

Write a var. **Modifies the original item in place**; the return value is the same modified item. Passing `null` as value removes the key.

`setVarDirect(itemStack: ItemStack, key: String, value: Any?): ItemStack`

## removeVar

Remove a var. **Does NOT modify the original item** — it returns the new item, so capture the return value.

`removeVar(itemStack: ItemStack, key: String): ItemStack`

## removeVarDirect

Remove a var. **Modifies the original item in place**; the return value is the same modified item.

`removeVarDirect(itemStack: ItemStack, key: String): ItemStack`

### Notes

- Values follow TabooLib `ItemTag` NBT semantics: `Boolean`/`UUID` are stored as strings (NBT has no boolean type); plain `String`/`Number`/`Map`/`List` are stored natively.
- Vars written here are the same persisted vars that feed back into the template pipeline on rebuild (`rebuildLore` etc. re-render placeholders from `sertraline_data`), so changing a var followed by `rebuildLore` updates the item's displayed lore.
