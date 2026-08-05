# Bug: `MorphiumBase.save()` — fehlendes `return` nach List/Collection-Delegation

## Betroffene Datei

`morphium-core/src/main/java/de/caluga/morphium/MorphiumBase.java` — Zeile 702–707

## Beschreibung

Die Methode `save(T o, String collection, AsyncOperationCallback<T> callback)` prüft, ob das übergebene Objekt eine `List` oder `Collection` ist, und delegiert in dem Fall an `saveList()`. Danach fehlt jedoch ein `return`, sodass der Code **durchfällt** und das List/Collection-Objekt zusätzlich als einzelnes Entity behandelt wird.

```java
public <T> void save(T o, String collection, final AsyncOperationCallback<T> callback) {
    if (o instanceof List) {
        saveList((List) o, collection, callback);
    } else if (o instanceof Collection) {
        saveList(new ArrayList<>((Collection) o), collection, callback);
    }
    // ⚠️ KEIN return — Code fällt durch!

    CascadeHelper.collectOrphanCandidates(this, o);

    try {
        if (getARHelper().getId(o) != null) {
            getWriterForClass(o.getClass()).store(o, collection, callback);  // versucht List als Entity zu speichern
        } else {
            getWriterForClass(o.getClass()).insert(o, collection, callback);
        }
        // ...
    }
}
```

## Auswirkung

Wenn `save()` mit einem `List`- oder `Collection`-Objekt aufgerufen wird:

1. `saveList()` wird korrekt aufgerufen und speichert die Elemente einzeln
2. Danach versucht der Code, das List-Objekt selbst als Entity zu speichern — `getARHelper().getId(o)` auf einer List aufzurufen ist undefiniertes Verhalten und kann zu Exceptions oder stillen Fehlern führen

## Fix

Nach jedem `saveList()`-Aufruf ein `return;` einfügen:

```java
if (o instanceof List) {
    saveList((List) o, collection, callback);
    return;
} else if (o instanceof Collection) {
    saveList(new ArrayList<>((Collection) o), collection, callback);
    return;
}
```

## Betrifft auch

Dieselbe Methode ohne `collection`-Parameter (`save(T o, AsyncOperationCallback<T> callback)`) sollte auf das gleiche Muster geprüft werden.
