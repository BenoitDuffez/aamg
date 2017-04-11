/**
 * Description of a field annoted @Column
 * Safe to search in Collections thanks to hashCode and equals
 */
class Column {
    String type;
    String name;
    public String fk = null;
    public String delete = "NO ACTION";
    public String update = "NO ACTION";

    public Column(String type, String name) {
        this.type = type;
        this.name = name;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Column) || other == null) {
            return false;
        }
        Column c = (Column) other;
        if ((c.type == null && type != null) || (c.type != null && type == null)) {
            return false;
        }
        if ((c.name == null && name != null) || (c.name != null && name == null)) {
            return false;
        }
        return (type == null || type.equals(c.type)) && (name == null || name.equals(c.name));
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (type == null ? 0 : type.hashCode());
        result = 31 * result + (name == null ? 0 : name.hashCode());
        return result;
    }
    @Override
    public String toString() {
        return String.format("%s %s", type, name);
    }
}

