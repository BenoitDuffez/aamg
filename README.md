# aamg
ActiveAndroid migration generator

This is a small tool that analyzes your code, and generates a migration script for your models.

## Example

Let's say that you have two models in your code:
```
bicou@dikkenek:~/aamg-demo/repo$ cat Item.java 
@Table(name = "Items")
public class Item extends Model {
    @Column
    public String name;

    @Column
    public Category category;

    @Column
    public Double price;
}

bicou@dikkenek:~/aamg-demo/repo$ cat Category.java 
@Table
public class Category extends Model {
    @Column
    public String name;

    public List<Item> items() {
        return getMany(Item.class, "Category");
    }
}
```

OK, now let's see what was added during the last commit:

```
bicou@dikkenek:~/aamg-demo/repo$ git log --pretty=oneline 
69889d65092d159bf2b2d6d7bc9f91ad33eb058b added price to item
cb3188e95149f9f78cc763906e4ce91fe4ba9818 first version
bicou@dikkenek:~/aamg-demo/repo$ git show 69889d65092d159bf2b2d6d7bc9f91ad33eb058b
commit 69889d65092d159bf2b2d6d7bc9f91ad33eb058b
Author: Benoit Duffez <benoit.duffez@upactivity.com>
Date:   Tue Apr 11 14:26:37 2017 +0200

    added price to item

diff --git a/Item.java b/Item.java
index a9f497e..97534b5 100644
--- a/Item.java
+++ b/Item.java
@@ -5,5 +5,8 @@ public class Item extends Model {
 
         @Column
         public Category category;
+
+        @Column
+        public Double price;
 } 
```

The price was added to `Item`. What would be the migration script?

```
bicou@dikkenek:~/aamg-demo/repo$ java -cp ~/projects/aamg/ GenerateAaMigration Item.java 69889d65092d159bf2b2d6d7bc9f91ad33eb058b
BEGIN TRANSACTION;

ALTER TABLE items ADD COLUMN price REAL;

COMMIT;
```

## Drop columns

The tool can be launched without the SHA1 argument, in which case it will compare the current state of the files with `HEAD`:

```
bicou@dikkenek:~/aamg-demo/repo$ git diff
diff --git a/Category.java b/Category.java
index 94ca9fd..e04e7eb 100644
--- a/Category.java
+++ b/Category.java
@@ -1,7 +1,10 @@
 @Table
 public class Category extends Model {
     @Column
-    public String name;
+    public int color;
+
+    @Column
+    public String description;
 
     public List<Item> items() {
         return getMany(Item.class, "Category");
```

Here the `name` column was dropped, and two other columns are created:

```
bicou@dikkenek:~/aamg-demo/repo$ java -cp ~/projects/aamg/ GenerateAaMigration Category.java 
BEGIN TRANSACTION;

CREATE TABLE category_backup (Id INTEGER PRIMARY KEY AUTOINCREMENT, color INTEGER, description TEXT);
INSERT INTO category_backup (Id) SELECT Id FROM category;
DROP TABLE category;
ALTER TABLE category_backup RENAME TO category;

COMMIT;
```

Since we can't drop columns with SQLite, a copy of the table is made and the data is inserted in the new table.

## What about TypeSerializers

OK now let's assume that we don't want to use an `int` in the code for the color, but an object. Let's see how it looks like:

```
bicou@dikkenek:~/aamg-demo/repo$ cat Color.java 
class Color {
    public int red, green, blue;
    Color(int r, int g, int b) {
        red = r;
        green = g;
        blue = b;
    }
}

bicou@dikkenek:~/aamg-demo/repo$ cat ColorSerializer.java 
import com.activeandroid.serializer.TypeSerializer;

public class ColorSerializer extends TypeSerializer {
    @Override
    public Class<?> getDeserializedType() {
        return Color.class;
    }

    @Override
    public Class<?> getSerializedType() {
        return Integer.class;
    }

    @Override
    public Integer serialize(Object data) {
        if (data == null || !(data instanceof Color)) {
            return null;
        }
        Color color = (Color) data;
        return color.red << 16 + color.green << 8 + color.blue;
    }

    @Override
    public Form deserialize(Object data) {
        if (data == null || !(data instanceof Integer)) {
            return null;
        }
        Integer color = (Integer) data;
        return new Color((color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff);
    }
}
```

The `TypeSerializer` simply stores the `Color` object in the DB by packing/unpacking an integer.

Now let's see our model:

```
bicou@dikkenek:~/aamg-demo/repo$ git diff Category.java
diff --git a/Category.java b/Category.java
index e04e7eb..c2aeddd 100644
--- a/Category.java
+++ b/Category.java
@@ -1,7 +1,7 @@
 @Table
 public class Category extends Model {
     @Column
-    public int color;
+    public Color rgb;
 
     @Column
     public String description;
```

Since we can't change a column type, we renamed it. See the result:

```
bicou@dikkenek:~/aamg-demo/repo$ java -cp ~/projects/aamg/ GenerateAaMigration Category.java 
BEGIN TRANSACTION;

CREATE TABLE category_backup (Id INTEGER PRIMARY KEY AUTOINCREMENT, rgb INTEGER, description TEXT);
INSERT INTO category_backup (Id, description) SELECT Id, description FROM category;
DROP TABLE category;
ALTER TABLE category_backup RENAME TO category;

COMMIT;
```

The tool correctly understood that the `rgb` column is stored as an `INTEGER` in the database.

