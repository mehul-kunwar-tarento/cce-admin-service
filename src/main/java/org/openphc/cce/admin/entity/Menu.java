package org.openphc.cce.admin.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One console screen (table menus). A DETAIL row points at its NAV parent through parentId. */
@Entity
@Table(name = "menus")
public class Menu {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String key;
	private String label;
	private String path;
	private String icon;

	@Enumerated(EnumType.STRING)
	private MenuType type;

	private UUID parentId;
	private int sortOrder;
	private boolean isActive;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private Instant createdAt;
	@Column(insertable = false)
	private Instant updatedAt;

	/** For JPA only. */
	protected Menu() {
	}

	public UUID getId() {
		return id;
	}

	public String getKey() {
		return key;
	}

	public String getLabel() {
		return label;
	}

	public String getPath() {
		return path;
	}

	public String getIcon() {
		return icon;
	}

	public MenuType getType() {
		return type;
	}

	public UUID getParentId() {
		return parentId;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public boolean isActive() {
		return isActive;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
