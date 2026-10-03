package io.github.mocchikon.hentie.service;

public record DeletedCount(int series, int chapters)
{
    public DeletedCount plus(DeletedCount other)
    {
        return new DeletedCount(series + other.series, chapters + other.chapters);
    }
}
