// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 全局运行状态（客户端单线程为主，这里做最小同步）。 */
public final class GuardState {

    private static final Object LOCK = new Object();

    private static final Map<Identifier, String> LOCAL_REGISTRATIONS = new LinkedHashMap<>();
    private static final Set<Identifier> BLOCKED = new LinkedHashSet<>();

    private static int optionalized;
    private static int seenTotal;
    private static boolean patchDone;
    private static boolean filterInstalled;
    private static boolean patchedConnection;

    private GuardState() {
    }

    public static void setLocalRegistrations(Map<Identifier, String> registrations) {
        synchronized (LOCK) {
            LOCAL_REGISTRATIONS.clear();
            LOCAL_REGISTRATIONS.putAll(registrations);
        }
    }

    public static Map<Identifier, String> localRegistrations() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(LOCAL_REGISTRATIONS);
        }
    }

    public static void addOptionalized(int count) {
        synchronized (LOCK) {
            optionalized += count;
        }
    }

    public static int optionalized() {
        synchronized (LOCK) {
            return optionalized;
        }
    }

    public static void setSeenTotal(int total) {
        synchronized (LOCK) {
            seenTotal = total;
        }
    }

    public static int seenTotal() {
        synchronized (LOCK) {
            return seenTotal;
        }
    }

    public static void markPatchDone() {
        synchronized (LOCK) {
            patchDone = true;
        }
    }

    public static boolean patchDone() {
        synchronized (LOCK) {
            return patchDone;
        }
    }

    public static void block(Collection<Identifier> ids) {
        synchronized (LOCK) {
            BLOCKED.clear();
            BLOCKED.addAll(ids);
            patchedConnection = !BLOCKED.isEmpty();
        }
    }

    public static boolean isBlocked(Identifier id) {
        return id != null && BLOCKED.contains(id);
    }

    public static int blockedCount() {
        synchronized (LOCK) {
            return BLOCKED.size();
        }
    }

    public static Set<Identifier> blockedIds() {
        synchronized (LOCK) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(BLOCKED));
        }
    }

    public static void markFilterInstalled() {
        synchronized (LOCK) {
            filterInstalled = true;
        }
    }

    public static boolean filterInstalled() {
        synchronized (LOCK) {
            return filterInstalled;
        }
    }

    public static boolean patchedConnection() {
        synchronized (LOCK) {
            return patchedConnection;
        }
    }

    public static void resetConnection() {
        synchronized (LOCK) {
            BLOCKED.clear();
            patchedConnection = false;
            filterInstalled = false;
        }
    }
}
