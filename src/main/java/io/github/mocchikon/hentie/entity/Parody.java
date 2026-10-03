package io.github.mocchikon.hentie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Entity
@Getter
@Setter
public class Parody implements Metadata
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String title;

    @ManyToMany(mappedBy = "parodies")
    private List<Chapter> chapters = new LinkedList<>();

    @Override
    public String getName()
    {
        return title;
    }

    @Override
    public void setName(String name)
    {
        this.title = name;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Parody parody = (Parody) o;

        if (id != null ? !id.equals(parody.id) : parody.id != null) return false;
        return title != null ? title.equals(parody.title) : parody.title == null;
    }

    @Override
    public int hashCode()
    {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (title != null ? title.hashCode() : 0);
        return result;
    }
}
