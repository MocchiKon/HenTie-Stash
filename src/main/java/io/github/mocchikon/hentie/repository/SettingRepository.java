package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String>
{
}
