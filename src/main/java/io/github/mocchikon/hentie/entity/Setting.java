package io.github.mocchikon.hentie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Key/value, so a new setting needs no schema change. */
@Entity
@Table(name = "app_setting")
@Getter
@Setter
@NoArgsConstructor
public class Setting
{
    @Id
    @Column(name = "setting_key", nullable = false, length = 100)
    private String key;

    @Column(name = "setting_value", length = 2000)
    private String value;

    public Setting(String key, String value)
    {
        this.key = key;
        this.value = value;
    }
}
