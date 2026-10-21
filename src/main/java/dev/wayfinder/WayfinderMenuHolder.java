package dev.wayfinder;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class WayfinderMenuHolder implements InventoryHolder {
    private final int page;
    private Inventory inventory;

    WayfinderMenuHolder(int page) {
        this.page = page;
    }

    int page() {
        return page;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
