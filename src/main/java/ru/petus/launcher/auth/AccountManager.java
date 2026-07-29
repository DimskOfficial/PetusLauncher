package ru.petus.launcher.auth;

import com.google.gson.reflect.TypeToken;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** accounts.json — the list of game accounts and which one is selected. */
public final class AccountManager {
    private static final AccountManager INSTANCE = new AccountManager();

    private final List<Account> accounts = new ArrayList<>();

    private AccountManager() {
        load();
    }

    public static AccountManager get() {
        return INSTANCE;
    }

    private void load() {
        try {
            if (Files.exists(AppDirs.accountsFile())) {
                List<Account> loaded = Json.GSON.fromJson(
                        Files.readString(AppDirs.accountsFile(), StandardCharsets.UTF_8),
                        new TypeToken<List<Account>>() { }.getType());
                if (loaded != null) {
                    loaded.removeIf(account -> account == null || account.name == null || account.name.isBlank());
                    accounts.addAll(loaded);
                }
            }
        } catch (Exception error) {
            Log.warn("Cannot read accounts.json: " + error);
        }
    }

    private void persist() {
        try {
            Json.write(AppDirs.accountsFile(), accounts);
        } catch (IOException error) {
            Log.error("Cannot save accounts.json", error);
        }
    }

    public synchronized List<Account> all() {
        return List.copyOf(accounts);
    }

    public synchronized Optional<Account> byId(String id) {
        return accounts.stream().filter(account -> account.id.equals(id)).findFirst();
    }

    /**
     * Keeps the PetusID-derived account in sync with the logged-in user. This is
     * the account used for our own servers, so it must always mirror PetusID.
     */
    public synchronized Account syncPetusAccount(String name, String uuid) {
        Account existing = accounts.stream()
                .filter(account -> account.type == Account.Type.PETUS)
                .findFirst()
                .orElse(null);
        if (existing == null) {
            existing = Account.petus(name, uuid);
            accounts.add(0, existing);
        } else {
            existing.name = name;
            existing.uuid = uuid;
        }
        if (Settings.get().selectedAccountId.isBlank()) {
            select(existing.id);
        }
        persist();
        return existing;
    }

    public synchronized Account addOffline(String name) {
        Account account = Account.offline(name.trim());
        accounts.add(account);
        persist();
        return account;
    }

    public synchronized Account addOrUpdateMicrosoft(Account account) {
        accounts.removeIf(existing -> existing.type == Account.Type.MICROSOFT
                && existing.uuid != null && existing.uuid.equals(account.uuid));
        accounts.add(account);
        persist();
        return account;
    }

    public synchronized void remove(String id) {
        accounts.removeIf(account -> account.id.equals(id) && account.type != Account.Type.PETUS);
        if (Settings.get().selectedAccountId.equals(id)) {
            Settings.get().selectedAccountId = accounts.isEmpty() ? "" : accounts.get(0).id;
            Settings.get().save();
        }
        persist();
    }

    public synchronized void select(String id) {
        Settings.get().selectedAccountId = id;
        Settings.get().save();
    }

    public synchronized Optional<Account> selected() {
        Optional<Account> chosen = byId(Settings.get().selectedAccountId);
        if (chosen.isPresent()) {
            return chosen;
        }
        return accounts.stream().findFirst();
    }

    /**
     * The account a given server should be played with. Our own servers always
     * use the PetusID account (their whitelist is PetusID based); anything else
     * uses whatever the user selected.
     */
    public synchronized Account forServer(boolean launcherOnly) {
        if (launcherOnly) {
            return accounts.stream()
                    .filter(account -> account.type == Account.Type.PETUS)
                    .findFirst()
                    .orElseGet(() -> selected().orElseThrow(
                            () -> new IllegalStateException("Нет ни одного аккаунта")));
        }
        return selected().orElseThrow(() -> new IllegalStateException("Выберите аккаунт в настройках"));
    }

    public synchronized void touch() {
        persist();
    }
}
