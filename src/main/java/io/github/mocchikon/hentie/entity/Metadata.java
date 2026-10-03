package io.github.mocchikon.hentie.entity;

/** Lets the service layer list, rename and merge the six metadata kinds generically. */
public interface Metadata
{
    Integer getId();

    String getName();

    void setName(String name);
}
