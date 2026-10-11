<template>
  <div>
    <header-bar show-menu show-logo show-task-center>
      <div class="listing-search">
        <input
          v-model.trim="inlineSearch"
          type="search"
          aria-label="在当前目录搜索"
          :placeholder="isMobile ? '搜索当前目录' : '在当前目录搜索文件'"
          @keyup.enter.stop.prevent="submitInlineSearch"
          @keyup.escape="clearInlineSearch"
        />
        <div v-if="inlineSearch" class="listing-search-actions">
          <button
            type="button"
            class="listing-search-clear"
            aria-label="清空搜索内容"
            title="清空搜索内容"
            @click="clearInlineSearch"
          >
            <AppIcon name="x" :size="18" />
          </button>
          <button
            type="button"
            class="listing-search-submit"
            aria-label="开始搜索"
            title="开始搜索"
            @click="submitInlineSearch"
          >
            <AppIcon name="search" :size="18" />
          </button>
        </div>
      </div>
      <template #primary-actions>
        <template v-if="!isMobile && authStore.user?.perm.create">
          <action
            class="header-primary-action"
            app-icon="file-new"
            label="新建文件"
            show="newFile"
          />
          <action
            class="header-primary-action"
            app-icon="folder-new"
            label="新建文件夹"
            show="newDir"
          />
        </template>
        <template v-if="!isMobile">
          <div
            ref="viewDropdownRef"
            class="view-mode-dropdown header-primary-control"
            :class="{ open: showViewDropdown }"
          >
            <action
              :app-icon="viewAppIcon"
              label="切换视图"
              :active="showViewDropdown"
              @action="toggleViewDropdown"
            />
            <div v-if="showViewDropdown" class="dropdown-menu">
              <button
                v-for="mode in viewModes"
                :key="mode.value"
                type="button"
                class="dropdown-item"
                :class="{ active: currentViewMode === mode.value }"
                :aria-pressed="currentViewMode === mode.value"
                @click.stop="selectViewMode(mode.value)"
              >
                <AppIcon :name="mode.icon" :size="19" />
                <span>{{ mode.label }}</span>
              </button>
              <template
                v-if="
                  currentViewMode === 'compact-grid' ||
                  currentViewMode === 'windows-icons'
                "
              >
                <div class="dropdown-divider"></div>
                <div class="dropdown-section-title">图标大小</div>
                <button
                  v-for="size in currentViewMode === 'windows-icons'
                    ? windowsIconSizes
                    : compactGridSizes"
                  :key="size.value"
                  type="button"
                  class="dropdown-item compact-grid-size-option"
                  :class="{
                    active:
                      (currentViewMode === 'windows-icons'
                        ? windowsIconSize
                        : compactGridSize) === size.value,
                  }"
                  :aria-pressed="
                    (currentViewMode === 'windows-icons'
                      ? windowsIconSize
                      : compactGridSize) === size.value
                  "
                  @click.stop="
                    currentViewMode === 'windows-icons'
                      ? selectWindowsIconSize(size.value)
                      : selectCompactGridSize(size.value)
                  "
                >
                  <AppIcon :name="size.icon" :size="19" />
                  <span>{{ size.label }}</span>
                </button>
              </template>
            </div>
          </div>
          <div
            ref="sortDropdownRef"
            class="sort-dropdown header-primary-control"
            :class="{ open: showSortDropdown }"
          >
            <action
              app-icon="sort"
              label="排序"
              :active="showSortDropdown"
              @action="toggleSortDropdown"
            />
            <div v-if="showSortDropdown" class="dropdown-menu">
              <button
                v-for="opt in sortOptions"
                :key="opt.by"
                type="button"
                class="dropdown-item"
                :class="{
                  active: sortIsOverridden && currentSortBy === opt.by,
                }"
                :aria-pressed="sortIsOverridden && currentSortBy === opt.by"
                @click.stop="cycleSort(opt.by)"
              >
                <AppIcon :name="opt.icon" :size="19" />
                <span>{{ opt.label }}</span>
                <AppIcon
                  v-if="sortIsOverridden && currentSortBy === opt.by"
                  class="sort-arrow"
                  :name="listingSortDirectionIcon(currentSortAsc)"
                  :size="17"
                />
              </button>
              <div class="dropdown-divider"></div>
              <div class="sort-state" role="status" aria-live="polite">
                <AppIcon name="sort" :size="18" />
                <span>当前：{{ sortStateLabel }}</span>
              </div>
              <button
                type="button"
                class="dropdown-item"
                :disabled="!sortIsOverridden"
                @click.stop="resetSortOverride"
              >
                <AppIcon name="undo" :size="19" />
                <span>恢复默认</span>
              </button>
            </div>
          </div>
        </template>
      </template>
      <template #actions>
        <template v-if="isMobile && authStore.user?.perm.create">
          <action app-icon="file-new" label="新建文件" show="newFile" />
          <action app-icon="folder-new" label="新建文件夹" show="newDir" />
        </template>
        <template v-if="!isMobile">
          <action
            v-if="headerButtons.share"
            app-icon="share"
            label="分享"
            show="share"
          />
          <action
            v-if="headerButtons.rename"
            app-icon="rename"
            label="重命名"
            show="rename"
          />
          <action
            v-if="headerButtons.copy"
            id="copy-button"
            app-icon="copy"
            label="复制文件"
            show="copy"
          />
          <action
            v-if="headerButtons.move"
            id="move-button"
            app-icon="move"
            label="移动文件"
            show="move"
          />
          <action
            v-if="headerButtons.analyze"
            app-icon="analysis"
            label="分析"
            @action="analyzeSelection"
          />
          <action
            v-if="headerButtons.delete"
            id="delete-button"
            app-icon="trash"
            label="删除"
            show="delete"
          />
        </template>

        <action
          v-if="headerButtons.shell"
          app-icon="terminal"
          label="终端"
          @action="layoutStore.toggleShell"
        />
        <action
          v-if="headerButtons.upload && !isMobile"
          app-icon="upload"
          id="upload-button"
          label="上传"
          @action="uploadFunc"
        />
        <!-- Mobile view controls stay in More because the phone header has no
             room for six persistent actions. Desktop renders these controls
             in the primary icon group above. -->
        <div
          v-if="isMobile"
          class="view-mode-dropdown"
          :class="{ open: showViewDropdown }"
          ref="viewDropdownRef"
        >
          <action
            :app-icon="viewAppIcon"
            label="切换视图"
            @action="toggleViewDropdown"
          />
          <div v-if="showViewDropdown" class="dropdown-menu">
            <button
              class="dropdown-back"
              type="button"
              @click.stop="showViewDropdown = false"
            >
              <AppIcon name="arrow-left" :size="18" />
              <span>选择视图</span>
            </button>
            <button
              v-for="mode in viewModes"
              :key="mode.value"
              class="dropdown-item"
              :class="{ active: currentViewMode === mode.value }"
              :aria-pressed="currentViewMode === mode.value"
              @click.stop="selectViewMode(mode.value)"
            >
              <AppIcon :name="mode.icon" :size="19" />
              <span>{{ mode.label }}</span>
            </button>
            <template
              v-if="
                currentViewMode === 'compact-grid' ||
                currentViewMode === 'windows-icons'
              "
            >
              <div class="dropdown-divider"></div>
              <div class="dropdown-section-title">图标大小</div>
              <button
                v-for="size in currentViewMode === 'windows-icons'
                  ? windowsIconSizes
                  : compactGridSizes"
                :key="size.value"
                class="dropdown-item compact-grid-size-option"
                :class="{
                  active:
                    (currentViewMode === 'windows-icons'
                      ? windowsIconSize
                      : compactGridSize) === size.value,
                }"
                type="button"
                :aria-pressed="
                  (currentViewMode === 'windows-icons'
                    ? windowsIconSize
                    : compactGridSize) === size.value
                "
                @click.stop="
                  currentViewMode === 'windows-icons'
                    ? selectWindowsIconSize(size.value)
                    : selectCompactGridSize(size.value)
                "
              >
                <AppIcon :name="size.icon" :size="19" />
                <span>{{ size.label }}</span>
              </button>
            </template>
          </div>
        </div>
        <!-- Sort Dropdown -->
        <div
          v-if="isMobile"
          class="sort-dropdown"
          :class="{ open: showSortDropdown }"
          ref="sortDropdownRef"
        >
          <action app-icon="sort" label="排序" @action="toggleSortDropdown" />
          <div v-if="showSortDropdown" class="dropdown-menu">
            <button
              class="dropdown-back"
              type="button"
              @click.stop="showSortDropdown = false"
            >
              <AppIcon name="arrow-left" :size="18" />
              <span>选择排序方式</span>
            </button>
            <button
              v-for="opt in sortOptions"
              :key="opt.by"
              class="dropdown-item"
              :class="{ active: sortIsOverridden && currentSortBy === opt.by }"
              :aria-pressed="sortIsOverridden && currentSortBy === opt.by"
              @click.stop="cycleSort(opt.by)"
            >
              <AppIcon :name="opt.icon" :size="19" />
              <span>{{ opt.label }}</span>
              <AppIcon
                v-if="sortIsOverridden && currentSortBy === opt.by"
                class="sort-arrow"
                :name="listingSortDirectionIcon(currentSortAsc)"
                :size="17"
              />
            </button>
            <div class="dropdown-divider"></div>
            <div class="sort-state" role="status" aria-live="polite">
              <AppIcon name="sort" :size="18" />
              <span>当前：{{ sortStateLabel }}</span>
            </div>
            <button
              class="dropdown-item"
              type="button"
              :disabled="!sortIsOverridden"
              @click.stop="resetSortOverride"
            >
              <AppIcon name="undo" :size="19" />
              <span>恢复默认</span>
            </button>
          </div>
        </div>
        <action
          v-if="headerButtons.download"
          app-icon="download"
          label="下载"
          @action="download"
          :counter="fileStore.selectedCount"
        />
        <action
          v-if="headerButtons.upload && isMobile"
          app-icon="upload"
          id="upload-button"
          label="上传"
          @action="uploadFunc"
        />
        <action
          v-if="headerButtons.transcode"
          app-icon="film"
          label="后台转码"
          :counter="fileStore.selectedCount"
          @action="transcode"
        />
        <action app-icon="info" label="详细信息" show="info" />
        <action
          app-icon="select"
          label="多选"
          @action="toggleMultipleSelection"
        />
      </template>
    </header-bar>

    <div
      v-if="shouldRenderMobileSelectionBar"
      id="file-selection"
      :class="{
        'file-selection-margin-bottom': fileStore.multiple,
      }"
    >
      <span v-if="fileStore.selectedCount > 0" class="selection-count">
        已选 {{ fileStore.selectedCount }} 项
      </span>
      <action app-icon="select" label="全选" @action="selectAll" />
      <action
        v-if="headerButtons.share"
        app-icon="share"
        label="分享"
        show="share"
      />
      <action
        v-if="headerButtons.rename"
        app-icon="rename"
        label="重命名"
        show="rename"
      />
      <action
        v-if="headerButtons.copy"
        app-icon="copy"
        label="复制"
        show="copy"
      />
      <action
        v-if="headerButtons.move"
        app-icon="move"
        label="移动"
        show="move"
      />
      <action
        v-if="headerButtons.analyze"
        app-icon="analysis"
        label="分析"
        @action="analyzeSelection"
      />
      <action
        v-if="headerButtons.delete"
        app-icon="trash"
        label="删除"
        show="delete"
      />
      <action app-icon="x" label="取消选择" @action="clearSelection" />
    </div>

    <div v-if="layoutStore.loading" class="loading-skeleton-wrapper">
      <LoadingSkeleton :count="12" :viewMode="skeletonViewMode" />
    </div>
    <template v-else>
      <div
        v-if="
          items.dirs.length + items.files.length === 0 &&
          !tagsStore.activeFilterTag
        "
        class="file-empty-state"
        role="status"
      >
        <span class="file-empty-state-icon" aria-hidden="true">
          <AppIcon name="folder" :size="32" />
        </span>
        <h2>这个文件夹是空的</h2>
        <p>可以新建文件或文件夹，也可以把文件上传到这里。</p>
        <div class="file-empty-state-actions">
          <button
            v-if="authStore.user?.perm.create"
            type="button"
            class="primary"
            @click="layoutStore.showHover('newDir')"
          >
            <AppIcon name="folder-new" :size="18" />
            新建文件夹
          </button>
          <button
            v-if="authStore.user?.perm.create"
            type="button"
            @click="layoutStore.showHover('newFile')"
          >
            <AppIcon name="file-new" :size="18" />
            新建文件
          </button>
          <button v-if="headerButtons.upload" type="button" @click="uploadFunc">
            <AppIcon name="upload" :size="18" />
            上传文件
          </button>
        </div>
        <input
          style="display: none"
          type="file"
          id="upload-input"
          @change="uploadInput($event)"
          multiple
        />
        <input
          style="display: none"
          type="file"
          id="upload-folder-input"
          @change="uploadInput($event)"
          webkitdirectory
          multiple
        />
      </div>
      <div
        v-else
        id="listing"
        ref="listing"
        class="file-icons"
        data-clear-on-click="true"
        :class="listingClass"
        :role="
          currentViewMode === 'details' && !isMobile ? undefined : 'listbox'
        "
        :aria-multiselectable="
          currentViewMode === 'details' && !isMobile
            ? undefined
            : fileStore.multiple
        "
        @click="handleEmptyAreaClick"
      >
        <!-- Detailed mode uses a semantic table at desktop widths and the
             shared FileKey/touch contract when the listing container narrows. -->
        <template v-if="currentViewMode !== 'details'">
          <div>
            <div class="listing-table-header">
              <div>
                <button
                  type="button"
                  class="name"
                  :aria-pressed="sortIsOverridden && currentSortBy === 'name'"
                  @click="sortByHeader('name')"
                >
                  <span>名称</span>
                </button>
                <button
                  type="button"
                  class="type"
                  :aria-pressed="sortIsOverridden && currentSortBy === 'type'"
                  @click="sortByHeader('type')"
                >
                  <span>类型</span>
                </button>
                <button
                  type="button"
                  class="size"
                  :aria-pressed="sortIsOverridden && currentSortBy === 'size'"
                  @click="sortByHeader('size')"
                >
                  <span>大小</span>
                </button>
                <button
                  type="button"
                  class="modified"
                  :aria-pressed="
                    sortIsOverridden && currentSortBy === 'modified'
                  "
                  @click="sortByHeader('modified')"
                >
                  <span>修改时间</span>
                </button>
                <span class="actions">操作</span>
              </div>
            </div>
          </div>

          <div v-if="tagsStore.activeFilterTag" class="tag-filter-indicator">
            <AppIcon
              name="tag"
              :size="22"
              :style="{ color: tagsStore.activeFilterTag.color }"
            />
            <span
              >正在按标签筛选：<strong>{{
                tagsStore.activeFilterTag.name
              }}</strong></span
            >
            <div
              class="tag-filter-scope"
              role="group"
              aria-label="标签筛选范围"
            >
              <button
                type="button"
                :class="{ active: tagsStore.filterMode === 'current' }"
                :aria-pressed="tagsStore.filterMode === 'current'"
                @click="tagsStore.setFilterMode('current')"
              >
                当前目录
              </button>
              <button
                type="button"
                :class="{ active: tagsStore.filterMode === 'global' }"
                :aria-pressed="tagsStore.filterMode === 'global'"
                @click="tagsStore.setFilterMode('global')"
              >
                全局
              </button>
            </div>
            <button
              class="tag-filter-clear-btn"
              type="button"
              aria-label="清除筛选"
              @click="tagsStore.setFilter(null)"
            >
              <AppIcon name="x" :size="18" />
            </button>
          </div>

          <div
            v-if="items.dirs.length + items.files.length === 0"
            class="message filtered-empty"
          >
            <AppIcon name="filter-clear" :size="22" />
            <span>当前目录没有匹配项，可切换为全局筛选或清除筛选</span>
          </div>
          <div
            v-else-if="listingSections.length === 0"
            class="message filtered-empty prefix-hidden-empty"
          >
            <AppIcon name="eye-off" :size="22" />
            <span>当前项目已按特殊前缀偏好隐藏，可在账户设置中调整。</span>
          </div>
          <template v-for="section in renderedSections" :key="section.id">
            <button
              v-if="section.kind === 'prefix'"
              type="button"
              class="listing-prefix-header"
              :data-prefix-group="section.prefix"
              :aria-expanded="section.expanded"
              :aria-label="prefixSectionAriaLabel(section)"
              :title="prefixSectionAriaLabel(section)"
              @click="togglePrefixSection(section.prefix || '')"
            >
              <span class="listing-prefix-token" aria-hidden="true">
                <AppIcon name="list-tree" :size="18" />
              </span>
              <span>{{ section.label }}</span>
              <span class="listing-prefix-count">{{ section.total }}</span>
              <AppIcon
                :name="section.expanded ? 'chevron-up' : 'chevron-down'"
                :size="20"
              />
            </button>
            <h2 v-else data-clear-on-click="true">{{ section.label }}</h2>
            <div
              v-if="section.expanded && section.items.length > 0"
              data-clear-on-click="true"
              @contextmenu="showContextMenu"
            >
              <item
                v-for="item in section.items"
                :key="base64(item.wirePath || item.path)"
                v-bind="item"
                :view-mode="currentViewMode"
                :visible-keys="visibleItemKeys"
                :register-item="registerItem"
              />
            </div>
          </template>
        </template>

        <template v-else>
          <div v-if="tagsStore.activeFilterTag" class="tag-filter-indicator">
            <AppIcon
              name="tag"
              :size="22"
              :style="{ color: tagsStore.activeFilterTag.color }"
            />
            <span
              >正在按标签筛选：<strong>{{
                tagsStore.activeFilterTag.name
              }}</strong></span
            >
            <div
              class="tag-filter-scope"
              role="group"
              aria-label="标签筛选范围"
            >
              <button
                type="button"
                :class="{ active: tagsStore.filterMode === 'current' }"
                :aria-pressed="tagsStore.filterMode === 'current'"
                @click="tagsStore.setFilterMode('current')"
              >
                当前目录
              </button>
              <button
                type="button"
                :class="{ active: tagsStore.filterMode === 'global' }"
                :aria-pressed="tagsStore.filterMode === 'global'"
                @click="tagsStore.setFilterMode('global')"
              >
                全局
              </button>
            </div>
            <button
              class="tag-filter-clear-btn"
              type="button"
              aria-label="清除筛选"
              @click="tagsStore.setFilter(null)"
            >
              <AppIcon name="x" :size="18" />
            </button>
          </div>
          <div
            v-if="listingSections.length === 0"
            class="message filtered-empty prefix-hidden-empty"
          >
            <AppIcon name="eye-off" :size="22" />
            <span>当前项目已按特殊前缀偏好隐藏，可在账户设置中调整。</span>
          </div>
          <div v-else-if="isMobile" class="details-mobile-list">
            <template v-for="section in renderedSections" :key="section.id">
              <button
                v-if="section.kind === 'prefix'"
                type="button"
                class="listing-prefix-header"
                :data-prefix-group="section.prefix"
                :aria-expanded="section.expanded"
                :aria-label="prefixSectionAriaLabel(section)"
                :title="prefixSectionAriaLabel(section)"
                @click="togglePrefixSection(section.prefix || '')"
              >
                <span class="listing-prefix-token" aria-hidden="true">
                  <AppIcon name="list-tree" :size="18" />
                </span>
                <span>{{ section.label }}</span>
                <span class="listing-prefix-count">{{ section.total }}</span>
                <AppIcon
                  :name="section.expanded ? 'chevron-up' : 'chevron-down'"
                  :size="20"
                />
              </button>
              <Item
                v-for="item in section.items"
                :key="base64(item.wirePath || item.path)"
                v-bind="item"
                view-mode="details"
                :visible-keys="visibleItemKeys"
                :register-item="registerItem"
              />
            </template>
          </div>
          <div v-else class="details-table-shell">
            <table class="details-table" aria-label="文件列表">
              <colgroup>
                <col class="details-col-name" />
                <col class="details-col-type" />
                <col class="details-col-size" />
                <col class="details-col-modified" />
                <col class="details-col-actions" />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col" :aria-sort="headerSortState('name')">
                    <button
                      type="button"
                      class="details-sort-button"
                      :class="{
                        'is-active':
                          sortIsOverridden && currentSortBy === 'name',
                      }"
                      :aria-pressed="
                        sortIsOverridden && currentSortBy === 'name'
                      "
                      @click="sortByHeader('name')"
                    >
                      名称
                      <AppIcon
                        v-if="sortIsOverridden && currentSortBy === 'name'"
                        :name="listingSortDirectionIcon(currentSortAsc)"
                        :size="16"
                      />
                    </button>
                  </th>
                  <th scope="col" :aria-sort="headerSortState('type')">
                    <button
                      type="button"
                      class="details-sort-button"
                      :class="{
                        'is-active':
                          sortIsOverridden && currentSortBy === 'type',
                      }"
                      :aria-pressed="
                        sortIsOverridden && currentSortBy === 'type'
                      "
                      @click="sortByHeader('type')"
                    >
                      类型
                      <AppIcon
                        v-if="sortIsOverridden && currentSortBy === 'type'"
                        :name="listingSortDirectionIcon(currentSortAsc)"
                        :size="16"
                      />
                    </button>
                  </th>
                  <th scope="col" :aria-sort="headerSortState('size')">
                    <button
                      type="button"
                      class="details-sort-button"
                      :class="{
                        'is-active':
                          sortIsOverridden && currentSortBy === 'size',
                      }"
                      :aria-pressed="
                        sortIsOverridden && currentSortBy === 'size'
                      "
                      @click="sortByHeader('size')"
                    >
                      大小
                      <AppIcon
                        v-if="sortIsOverridden && currentSortBy === 'size'"
                        :name="listingSortDirectionIcon(currentSortAsc)"
                        :size="16"
                      />
                    </button>
                  </th>
                  <th scope="col" :aria-sort="headerSortState('modified')">
                    <button
                      type="button"
                      class="details-sort-button"
                      :class="{
                        'is-active':
                          sortIsOverridden && currentSortBy === 'modified',
                      }"
                      :aria-pressed="
                        sortIsOverridden && currentSortBy === 'modified'
                      "
                      @click="sortByHeader('modified')"
                    >
                      修改时间
                      <AppIcon
                        v-if="sortIsOverridden && currentSortBy === 'modified'"
                        :name="listingSortDirectionIcon(currentSortAsc)"
                        :size="16"
                      />
                    </button>
                  </th>
                  <th scope="col" class="details-actions-heading">操作</th>
                </tr>
              </thead>
              <tbody @contextmenu="showContextMenu">
                <template v-for="section in renderedSections" :key="section.id">
                  <tr
                    v-if="section.kind === 'prefix'"
                    class="details-prefix-row"
                  >
                    <td colspan="5">
                      <button
                        type="button"
                        class="listing-prefix-header"
                        :data-prefix-group="section.prefix"
                        :aria-expanded="section.expanded"
                        :aria-label="prefixSectionAriaLabel(section)"
                        :title="prefixSectionAriaLabel(section)"
                        @click="togglePrefixSection(section.prefix || '')"
                      >
                        <span class="listing-prefix-token" aria-hidden="true">
                          <AppIcon name="list-tree" :size="18" />
                        </span>
                        <span>{{ section.label }}</span>
                        <span class="listing-prefix-count">{{
                          section.total
                        }}</span>
                        <AppIcon
                          :name="
                            section.expanded ? 'chevron-up' : 'chevron-down'
                          "
                          :size="20"
                        />
                      </button>
                    </td>
                  </tr>
                  <DetailedTableRow
                    v-for="item in section.items"
                    :key="base64(item.wirePath || item.path)"
                    v-bind="item"
                    :visible-keys="visibleItemKeys"
                    :register-item="registerItem"
                  />
                </template>
              </tbody>
            </table>
          </div>
        </template>

        <context-menu
          :show="isContextMenuVisible"
          :pos="contextMenuPos"
          @hide="hideContextMenu"
        >
          <MenuItemButton
            v-if="headerButtons.share"
            icon="share"
            label="分享"
            @click="showContextPrompt('share')"
          />
          <MenuItemButton
            v-if="headerButtons.rename"
            icon="rename"
            label="重命名"
            @click="showContextPrompt('rename')"
          />
          <MenuItemButton
            v-if="headerButtons.copy"
            id="copy-button"
            icon="copy"
            label="复制文件"
            @click="showContextPrompt('copy')"
          />
          <MenuItemButton
            v-if="headerButtons.move"
            id="move-button"
            icon="move"
            label="移动文件"
            @click="showContextPrompt('move')"
          />
          <MenuItemButton
            v-if="headerButtons.delete"
            id="delete-button"
            icon="trash"
            label="删除"
            tone="danger"
            @click="showContextPrompt('delete')"
          />
          <MenuItemButton
            v-if="headerButtons.download"
            icon="download"
            label="下载"
            @click="runContextDownload"
          />
          <MenuItemButton
            v-if="headerButtons.analyze"
            icon="analysis"
            label="分析"
            @click="runContextAnalysis"
          />
          <MenuItemButton
            v-if="headerButtons.transcode"
            icon="film"
            label="后台转码"
            @click="
              hideContextMenu();
              transcode();
            "
          />
          <MenuItemButton
            icon="info"
            label="详细信息"
            @click="showContextPrompt('info')"
          />
        </context-menu>

        <input
          style="display: none"
          type="file"
          id="upload-input"
          @change="uploadInput($event)"
          multiple
        />
        <input
          style="display: none"
          type="file"
          id="upload-folder-input"
          @change="uploadInput($event)"
          webkitdirectory
          multiple
        />

        <div
          v-if="!isMobile"
          :class="{ active: fileStore.multiple }"
          id="multiple-selection"
        >
          <div class="selection-info">
            <AppIcon name="circle-check" :size="20" />
            <span v-if="fileStore.selectedCount > 0"> 已选择 </span>
            <span v-else>多选模式已开启</span>
          </div>
          <div class="selection-actions">
            <button
              class="selection-btn"
              @click="selectAll"
              title="全选"
              aria-label="全选"
            >
              <AppIcon name="select" :size="19" />
              <span>全选</span>
            </button>
            <button
              v-if="fileStore.selectedCount > 0"
              class="selection-btn"
              @click="invertSelection"
              title="反选"
              aria-label="反选"
            >
              <AppIcon name="flip" :size="19" />
              <span>反选</span>
            </button>
            <template v-if="fileStore.selectedCount > 0">
              <button
                v-if="headerButtons.rename"
                class="selection-btn action-btn"
                @click="layoutStore.showHover('rename')"
              >
                <AppIcon name="rename" :size="19" />
                <span>重命名</span>
              </button>
              <button
                v-if="headerButtons.copy"
                class="selection-btn action-btn"
                @click="layoutStore.showHover('copy')"
              >
                <AppIcon name="copy" :size="19" />
                <span>复制文件</span>
              </button>
              <button
                v-if="headerButtons.move"
                class="selection-btn action-btn"
                @click="layoutStore.showHover('move')"
              >
                <AppIcon name="move" :size="19" />
                <span>移动文件</span>
              </button>
              <button
                v-if="headerButtons.download"
                class="selection-btn action-btn"
                @click="download"
              >
                <AppIcon name="download" :size="19" />
                <span>下载</span>
              </button>
              <button
                v-if="headerButtons.analyze"
                class="selection-btn action-btn"
                @click="analyzeSelection"
              >
                <AppIcon name="chart-storage" :size="19" />
                <span>分析</span>
              </button>
              <button
                v-if="headerButtons.transcode"
                class="selection-btn action-btn"
                @click="transcode"
              >
                <AppIcon name="film" :size="19" /><span>后台转码</span>
              </button>
              <button
                v-if="headerButtons.delete"
                class="selection-btn action-btn danger"
                @click="layoutStore.showHover('delete')"
              >
                <AppIcon name="trash" :size="19" />
                <span>删除</span>
              </button>
            </template>
            <button
              class="selection-btn close-btn"
              @click="
                () => {
                  fileStore.clearSelection();
                }
              "
              title="关闭"
              aria-label="关闭"
            >
              <AppIcon name="x" :size="18" />
            </button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import {
  operationAcknowledgedTarget,
  operationDestinationRoute,
  operationSameTarget,
} from "@/utils/resourceOperationWire";
import { useClipboardStore } from "@/stores/clipboard";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { useTagsStore } from "@/stores/tags";
import { useListingPreferencesStore } from "@/stores/listingPreferences";

import { users, files as api } from "@/api";
import { enableExec } from "@/utils/constants";
import * as upload from "@/utils/upload";
import {
  cycleListingSort,
  normalizeViewMode,
  parseFileViewMode,
  selectForContextMenu,
  sortListingItems,
} from "@/utils/fileListing";
import {
  isEditableKeyboardTarget,
  shouldRenderMobileSelection,
} from "@/utils/layoutContract";
import { throttle } from "lodash-es";
import { Base64 } from "js-base64";

import HeaderBar from "@/components/header/HeaderBar.vue";
import Action from "@/components/header/Action.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import MenuItemButton from "@/components/ui/MenuItemButton.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import Item from "@/components/files/ListingItem.vue";
import DetailedTableRow from "@/components/files/DetailedTableRow.vue";
import ContextMenu from "@/components/ContextMenu.vue";
import LoadingSkeleton from "@/components/LoadingSkeleton.vue";
import type {
  ResourceItem,
  PasteItem,
  ConflictingResource,
  DownloadFormat,
} from "@/types/file";
import type { ViewModeType } from "@/types/user";
import {
  computed,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import {
  useRoute,
  useRouter,
  onBeforeRouteUpdate,
  onBeforeRouteLeave,
} from "vue-router";
import { useNavigationStore } from "@/stores/navigation";
import { useAccountPreferencesStore } from "@/stores/accountPreferences";
import { storeToRefs } from "pinia";
import { removePrefix } from "@/api/utils";
import {
  normalizeFilesRouteBase,
  normalizeSearchBase,
} from "@/utils/searchPath";
import { isExternalFileDrag } from "@/utils/fileDrag";
import { resourceOpenRoute } from "@/utils/archivePath";
import {
  buildListingSections,
  paginateListingSections,
} from "@/utils/listingPreferences";
import {
  listingGridSizeIcon,
  listingSortDirectionIcon,
  listingSortIcon,
  listingViewIcon,
} from "@/utils/listingIconSemantics";
import {
  normalizeSorting,
  readGuestSorting,
  writeGuestSorting,
} from "@/utils/sortingPreferences";

const showLimit = ref<number>(50);
const tagsStore = useTagsStore();
const dragCounter = ref<number>(0);
// HeaderBar renders through this component's slots. Use the same viewport
// media query as HeaderBar instead of the listing container width: the
// listing can be narrower than the viewport because of the sidebar, and a
// ResizeObserver update would otherwise move desktop actions into the mobile
// overflow slot (or vice versa) after the header has already rendered.
const mobileMediaQuery =
  typeof window !== "undefined"
    ? window.matchMedia("(max-width: 899px)")
    : undefined;
const isMobile = ref(mobileMediaQuery?.matches ?? false);
const updateMobileViewport = (event?: MediaQueryListEvent) => {
  isMobile.value = event?.matches ?? mobileMediaQuery?.matches ?? false;
};
const itemWeight = ref<number>(0);
const isContextMenuVisible = ref<boolean>(false);
const contextMenuPos = ref<{ x: number; y: number }>({ x: 0, y: 0 });

const $showError = inject<IToastError>("$showError")!;

const clipboardStore = useClipboardStore();
const authStore = useAuthStore();
const fileStore = useFileStore();
const listingScope = fileStore.scope;
const layoutStore = useLayoutStore();
const listingPreferencesStore = useListingPreferencesStore();
const accountPreferencesStore = useAccountPreferencesStore();
const route = useRoute();
const router = useRouter();

// View mode dropdown
const showViewDropdown = ref<boolean>(false);
const viewDropdownRef = ref<HTMLElement | null>(null);
const storedViewMode = localStorage.getItem("nas-file-browser-view-mode");
const currentViewMode = ref<ViewModeType>(
  parseFileViewMode(route.query.view) ??
    normalizeViewMode(storedViewMode ?? authStore.user?.viewMode)
);
const viewModes = [
  {
    value: "mosaic" as ViewModeType,
    icon: listingViewIcon("mosaic"),
    label: "详细网格",
  },
  {
    value: "compact-grid" as ViewModeType,
    icon: listingViewIcon("compact-grid"),
    label: "紧凑网格",
  },
  {
    value: "windows-icons" as ViewModeType,
    icon: listingViewIcon("windows-icons"),
    label: "无界网格",
  },
  {
    value: "details" as ViewModeType,
    icon: listingViewIcon("details"),
    label: "详细列表",
  },
  {
    value: "compact-list" as ViewModeType,
    icon: listingViewIcon("compact-list"),
    label: "紧凑列表",
  },
];
type CompactGridSize = "small" | "medium" | "large" | "xlarge";
const compactGridSizes: Array<{
  value: CompactGridSize;
  icon: AppIconName;
  label: string;
}> = [
  { value: "small", icon: listingGridSizeIcon("small"), label: "小图标" },
  { value: "medium", icon: listingGridSizeIcon("medium"), label: "中图标" },
  { value: "large", icon: listingGridSizeIcon("large"), label: "大图标" },
  {
    value: "xlarge",
    icon: listingGridSizeIcon("xlarge"),
    label: "超大图标",
  },
];
const windowsIconSizes = compactGridSizes;
const windowsIconSizeKey = "nas-file-browser-windows-icon-size";
const storedWindowsIconSize = localStorage.getItem(windowsIconSizeKey);
const windowsIconSize = ref<CompactGridSize>(
  windowsIconSizes.some((size) => size.value === storedWindowsIconSize)
    ? (storedWindowsIconSize as CompactGridSize)
    : "medium"
);
const storedCompactGridSize = localStorage.getItem(
  "nas-file-browser-compact-grid-size"
);
const compactGridSize = ref<CompactGridSize>(
  compactGridSizes.some((item) => item.value === storedCompactGridSize)
    ? (storedCompactGridSize as CompactGridSize)
    : "medium"
);

// Sort dropdown
const showSortDropdown = ref<boolean>(false);
const sortDropdownRef = ref<HTMLElement | null>(null);
const initialSorting = normalizeSorting(authStore.user?.sorting) ??
  (!authStore.user?.id ? readGuestSorting() : null) ??
  normalizeSorting(fileStore.req?.sorting) ?? { by: "name", asc: true };
const accountSortBy = ref<string>(initialSorting.by);
const accountSortAsc = ref<boolean>(initialSorting.asc);
const currentSortBy = ref<string>(accountSortBy.value);
const currentSortAsc = ref<boolean>(accountSortAsc.value);
const sortIsOverridden = ref(false);
const inlineSearch = ref("");
const sortOptions = [
  { by: "name", icon: listingSortIcon("name"), label: "名称" },
  { by: "size", icon: listingSortIcon("size"), label: "大小" },
  {
    by: "modified",
    icon: listingSortIcon("modified"),
    label: "修改时间",
  },
  { by: "type", icon: listingSortIcon("type"), label: "类型" },
];

const sortStateLabel = computed(() => {
  if (!sortIsOverridden.value) return "默认";
  return currentSortAsc.value ? "升序" : "降序";
});

const { req } = storeToRefs(fileStore);

const navigation = useNavigationStore();
const listingUserId = authStore.user?.id;
let listingRoute = route.fullPath;
let leavingDirectory = false;
const returningState = navigation.takeDirectoryState(listingRoute);
if (returningState) {
  currentSortBy.value = returningState.sortBy;
  currentSortAsc.value = returningState.sortAsc;
  sortIsOverridden.value = returningState.sortOverridden;
  currentViewMode.value =
    parseFileViewMode(route.query.view) ??
    normalizeViewMode(storedViewMode ?? authStore.user?.viewMode);
  inlineSearch.value = returningState.search;
  showLimit.value = Math.max(
    50,
    Math.min(returningState.limit, fileStore.req?.items.length || 50)
  );
  tagsStore.activeFilter = returningState.tag;
  tagsStore.filterMode = returningState.filterMode;
}

function saveDirectoryState() {
  if (navigation.userId !== listingUserId) return;
  navigation.rememberDirectory(listingRoute, {
    scrollY: window.scrollY,
    limit: showLimit.value,
    sortBy: currentSortBy.value,
    sortAsc: currentSortAsc.value,
    sortOverridden: sortIsOverridden.value,
    viewMode: currentViewMode.value,
    search: inlineSearch.value,
    tag: tagsStore.activeFilter,
    filterMode: tagsStore.filterMode,
  });
}

onBeforeRouteLeave(() => {
  saveDirectoryState();
  leavingDirectory = true;
});
onBeforeRouteUpdate((to, from) => {
  saveDirectoryState();
  hideContextMenu();
  leavingDirectory = to.path !== from.path;
  if (!leavingDirectory) listingRoute = to.fullPath;
});

watch(
  () => route.query.view,
  (value) => {
    currentViewMode.value =
      parseFileViewMode(value) ??
      normalizeViewMode(
        localStorage.getItem("nas-file-browser-view-mode") ??
          authStore.user?.viewMode
      );
    nextTick(() => {
      setItemWeight();
      fillWindow();
    });
  }
);

const listing = ref<HTMLElement | null>(null);
let listingResizeObserver: ResizeObserver | null = null;
const itemElements = new Map<string, HTMLElement>();
const registerItem = (key: string, element: HTMLElement | null) => {
  const normalized = key;
  if (element) itemElements.set(normalized, element);
  else itemElements.delete(normalized);
};
const scrollItemIntoView = (key: string, block: ScrollLogicalPosition) => {
  itemElements.get(key)?.scrollIntoView({ block });
};

const items = computed(() => {
  const dirs: ResourceItem[] = [];
  const files: ResourceItem[] = [];

  // Get the parent path for constructing full paths
  const parentPath = fileStore.req?.path ?? "";

  fileStore.req?.items.forEach((item) => {
    // Build the full path for tag matching
    const fullPath =
      item.path ||
      (parentPath
        ? parentPath.replace(/\/$/, "") + "/" + item.name
        : "/" + item.name);

    // Apply tag filter (files and directories)
    if (!tagsStore.matchesFilter(fullPath, item.wirePath)) {
      return; // skip this item
    }

    if (item.isDir) {
      dirs.push(item);
    } else {
      files.push(item);
    }
  });

  return {
    dirs: sortListingItems(dirs, currentSortBy.value, currentSortAsc.value),
    files: sortListingItems(files, currentSortBy.value, currentSortAsc.value),
  };
});

const listingSections = computed(() =>
  buildListingSections(
    items.value.dirs,
    items.value.files,
    listingPreferencesStore.preferences
  )
);

const navigableItems = computed<ResourceItem[]>(() =>
  listingSections.value.flatMap((section) =>
    section.expanded ? section.items : []
  )
);

const renderedSections = computed(() =>
  paginateListingSections(listingSections.value, showLimit.value)
);

const visibleItemKeys = computed(() =>
  navigableItems.value.map((item) => fileStore.keyFor(item)).filter(Boolean)
);

const togglePrefixSection = async (prefix: string) => {
  const section = listingSections.value.find(
    (candidate) => candidate.prefix === prefix
  );
  if (!section) return;
  const selectionScope = fileStore.scope;
  const selectionSnapshot = {
    selected: [...fileStore.selected],
    focused: fileStore.focused,
    rangeAnchor: fileStore.rangeAnchor,
  };
  if (section.expanded) {
    const hiddenKeys = new Set(
      section.items.map((item) => fileStore.keyFor(item))
    );
    fileStore.setSelected(
      fileStore.selected.filter((key) => !hiddenKeys.has(key))
    );
  }
  try {
    await listingPreferencesStore.updateRule(prefix, {
      expanded: !section.expanded,
    });
  } catch (error) {
    if (fileStore.scope === selectionScope) fileStore.$patch(selectionSnapshot);
    $showError(error instanceof Error ? error : new Error("分组偏好保存失败"));
  }
};

const prefixSectionAriaLabel = (section: {
  label: string;
  total: number;
  expanded: boolean;
}) =>
  `${section.label}，${section.total} 项，${section.expanded ? "已展开" : "已折叠"}`;

const skeletonViewMode = computed(() => {
  const mode = currentViewMode.value;
  if (mode === "details" || mode === "compact-list") return "list";
  if (mode === "compact-grid" || mode === "windows-icons") return "mosaic";
  return mode;
});

const listingClass = computed(() => ({
  [currentViewMode.value]: true,
  ...(currentViewMode.value === "compact-grid"
    ? { [`compact-grid-size-${compactGridSize.value}`]: true }
    : {}),
  ...(currentViewMode.value === "windows-icons"
    ? { [`windows-icons-size-${windowsIconSize.value}`]: true }
    : {}),
}));

const viewAppIcon = computed<AppIconName>(() => {
  const icons: Record<ViewModeType, AppIconName> = {
    mosaic: "view-mosaic",
    "compact-grid": "view-compact-grid",
    "windows-icons": "view-compact-grid",
    details: "view-details",
    "compact-list": "view-compact-list",
  };
  return icons[currentViewMode.value];
});

const headerButtons = computed(() => {
  return {
    // The backend models upload permission as `create` (the frontend type
    // keeps the historical optional `upload` field for fixture compatibility).
    // Do not gate the upload affordance on a field that the auth token never
    // sends, otherwise real users silently lose the upload action.
    upload: authStore.user?.perm.create,
    download: authStore.user?.perm.download,
    transcode:
      authStore.user?.perm.download &&
      authStore.user?.perm.create &&
      (fileStore.selectedCount === 0 ||
        fileStore.selectedItems.some(
          (item) => item.isDir || item.type === "video"
        )),
    shell: authStore.user?.perm.execute && enableExec,
    delete: fileStore.selectedCount > 0 && authStore.user?.perm.delete,
    rename: fileStore.selectedCount > 0 && authStore.user?.perm.rename,
    share:
      fileStore.selectedCount === 1 &&
      authStore.user?.perm.share &&
      authStore.user?.perm.download,
    move: fileStore.selectedCount > 0 && authStore.user?.perm.rename,
    copy: fileStore.selectedCount > 0 && authStore.user?.perm.create,
    analyze: fileStore.selectedCount > 0 && authStore.user?.perm.download,
  };
});

const shouldRenderMobileSelectionBar = computed(() =>
  shouldRenderMobileSelection(
    isMobile.value,
    fileStore.multiple,
    fileStore.selectedCount
  )
);

watch(req, () => {
  // Reset the show value
  showLimit.value = 50;

  // Sync sort state from server
  if (fileStore.req?.sorting) {
    const saved = normalizeSorting(authStore.user?.sorting);
    const fallback = normalizeSorting(fileStore.req.sorting);
    const next = saved ?? fallback;
    if (next) {
      accountSortBy.value = next.by;
      accountSortAsc.value = next.asc;
      if (!sortIsOverridden.value) {
        currentSortBy.value = accountSortBy.value;
        currentSortAsc.value = accountSortAsc.value;
      }
    }
  }

  nextTick(() => {
    // Ensures that the listing is displayed
    // How much every listing item affects the window height
    setItemWeight();

    // Scroll to the item opened previously
    if (!revealPreviousItem()) {
      // Fill and fit the window with listing items
      fillWindow(true);
    }
  });
});

onMounted(() => {
  // How much every listing item affects the window height
  setItemWeight();

  // Scroll to the item opened previously
  if (returningState) {
    nextTick(() =>
      window.scrollTo({ top: returningState.scrollY, behavior: "instant" })
    );
  } else if (!revealPreviousItem()) {
    // Fill and fit the window with listing items
    fillWindow(true);
  }

  // Add the needed event listeners to the window and document.
  window.addEventListener("keydown", keyEvent);
  window.addEventListener("scroll", scrollEvent);
  document.addEventListener("click", handleOutsideClick);
  window.addEventListener("pagehide", saveDirectoryState);
  if (mobileMediaQuery?.addEventListener) {
    mobileMediaQuery.addEventListener("change", updateMobileViewport);
  } else {
    mobileMediaQuery?.addListener(updateMobileViewport);
  }
  // Save the directory identity immediately; later snapshots capture its scroll.
  if (!returningState) saveDirectoryState();

  if (typeof ResizeObserver !== "undefined") {
    listingResizeObserver = new ResizeObserver(resizeListing);
    if (listing.value) listingResizeObserver.observe(listing.value);
  } else if (listing.value) {
    setItemWeight();
  }

  if (!authStore.user?.perm.create) return;
  document.addEventListener("dragover", preventDefault);
  document.addEventListener("dragenter", dragEnter);
  document.addEventListener("dragleave", dragLeave);
  document.addEventListener("drop", drop);
});

onBeforeUnmount(() => {
  if (!leavingDirectory) saveDirectoryState();
  window.removeEventListener("pagehide", saveDirectoryState);
  // Remove event listeners before destroying this page.
  window.removeEventListener("keydown", keyEvent);
  window.removeEventListener("scroll", scrollEvent);
  document.removeEventListener("click", handleOutsideClick);
  if (mobileMediaQuery?.removeEventListener) {
    mobileMediaQuery.removeEventListener("change", updateMobileViewport);
  } else {
    mobileMediaQuery?.removeListener(updateMobileViewport);
  }
  listingResizeObserver?.disconnect();
  listingResizeObserver = null;

  if (authStore.user && !authStore.user?.perm.create) return;
  document.removeEventListener("dragover", preventDefault);
  document.removeEventListener("dragenter", dragEnter);
  document.removeEventListener("dragleave", dragLeave);
  document.removeEventListener("drop", drop);
});

watch(listing, (next, previous) => {
  if (previous) listingResizeObserver?.unobserve(previous);
  if (!next) return;
  listingResizeObserver?.observe(next);
});

const base64 = (name: string) => Base64.encodeURI(name);

const keyEvent = (event: KeyboardEvent) => {
  if (fileStore.scope !== listingScope) return;
  if (isEditableKeyboardTarget(event.target)) return;

  // No prompts are shown
  if (layoutStore.currentPrompt !== null) {
    return;
  }

  if (event.key === "Escape") {
    // Reset files selection.
    fileStore.clearSelection();
  }

  // Arrow key navigation
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    const allItems = navigableItems.value.filter((item) =>
      fileStore.keyFor(item)
    );
    if (allItems.length === 0) return;

    const currentKey = fileStore.focused ?? fileStore.selected.at(-1);
    const currentPosition = currentKey
      ? allItems.findIndex((item) => fileStore.keyFor(item) === currentKey)
      : -1;
    const newPosition =
      event.key === "ArrowDown"
        ? Math.min(currentPosition + 1, allItems.length - 1)
        : currentPosition < 0
          ? allItems.length - 1
          : Math.max(currentPosition - 1, 0);
    const newKey = fileStore.keyFor(allItems[newPosition]);

    // Shift+Arrow for range selection
    if (event.shiftKey) {
      const orderedKeys = allItems.map((item) => fileStore.keyFor(item));
      fileStore.selectRange(
        orderedKeys,
        newKey,
        event.ctrlKey || event.metaKey
      );
    } else {
      fileStore.selectOnly(newKey);
    }

    showLimit.value = Math.max(showLimit.value, newPosition + 1);

    // Scroll selected item into view
    nextTick(() => {
      scrollItemIntoView(newKey, "nearest");
    });
    return;
  }

  // Enter key - open selected item
  if (event.key === "Enter") {
    if (fileStore.selectedCount === 1) {
      const item = fileStore.selectedItems[0];
      if (item) {
        router.push(resourceOpenRoute(item));
      }
    }
    return;
  }

  // Home - jump to first item
  if (event.key === "Home" && !event.ctrlKey && !event.metaKey) {
    event.preventDefault();
    const allItems = navigableItems.value.filter((item) =>
      fileStore.keyFor(item)
    );
    if (allItems.length > 0) {
      const targetKey = fileStore.keyFor(allItems[0]);
      const orderedKeys = allItems.map((item) => fileStore.keyFor(item));
      if (event.shiftKey) fileStore.selectRange(orderedKeys, targetKey);
      else fileStore.selectOnly(targetKey);
      nextTick(() => scrollItemIntoView(targetKey, "nearest"));
    }
    return;
  }

  // End - jump to last item
  if (event.key === "End" && !event.ctrlKey && !event.metaKey) {
    event.preventDefault();
    const allItems = navigableItems.value.filter((item) =>
      fileStore.keyFor(item)
    );
    if (allItems.length > 0) {
      const targetKey = fileStore.keyFor(allItems[allItems.length - 1]);
      const orderedKeys = allItems.map((item) => fileStore.keyFor(item));
      if (event.shiftKey) fileStore.selectRange(orderedKeys, targetKey);
      else fileStore.selectOnly(targetKey);
      showLimit.value = allItems.length;
      nextTick(() => scrollItemIntoView(targetKey, "nearest"));
    }
    return;
  }

  // Page Up / Page Down - jump by visible page size
  if (event.key === "PageDown" || event.key === "PageUp") {
    event.preventDefault();
    const allItems = navigableItems.value.filter((item) =>
      fileStore.keyFor(item)
    );
    if (allItems.length === 0) return;

    // Estimate visible items from viewport height
    const pageSize = Math.max(5, Math.floor((window.innerHeight - 200) / 60));
    const currentKey = fileStore.focused ?? fileStore.selected.at(-1);

    // Find position in allItems array
    const pos = currentKey
      ? allItems.findIndex((item) => fileStore.keyFor(item) === currentKey)
      : -1;
    let newPos: number;
    if (event.key === "PageDown") {
      newPos =
        pos < 0 ? pageSize - 1 : Math.min(pos + pageSize, allItems.length - 1);
    } else {
      newPos = pos < 0 ? 0 : Math.max(pos - pageSize, 0);
    }

    const target = allItems[newPos];
    if (target) {
      const targetKey = fileStore.keyFor(target);
      if (event.shiftKey) {
        fileStore.selectRange(
          allItems.map((item) => fileStore.keyFor(item)),
          targetKey,
          event.ctrlKey || event.metaKey
        );
      } else {
        fileStore.selectOnly(targetKey);
      }
      showLimit.value = Math.max(showLimit.value, newPos + 1);
      nextTick(() => {
        scrollItemIntoView(targetKey, "nearest");
      });
    }
    return;
  }

  if (event.key === "Delete") {
    if (!authStore.user?.perm.delete || fileStore.selectedCount == 0) return;

    // Show delete prompt.
    layoutStore.showHover("delete");
  }

  if (event.key === "F2") {
    if (!authStore.user?.perm.rename || fileStore.selectedCount === 0) return;

    event.preventDefault();
    layoutStore.showHover("rename");
  }

  // Space key - Quick Preview
  if (event.key === " " || event.code === "Space") {
    if (fileStore.selectedCount !== 1) return;
    event.preventDefault();
    const item = fileStore.selectedItems[0];
    if (item && !item.isDir) {
      layoutStore.showHover({
        prompt: "quick-preview",
        props: {
          item: {
            name: item.name,
            url: item.url,
            type: item.type,
            size: item.size,
            modified: item.modified,
            path: item.path,
            wirePath: item.wirePath,
            extension: item.extension || "",
          },
          items: navigableItems.value,
        },
      });
    }
  }

  // Ctrl is pressed
  if (!event.ctrlKey && !event.metaKey) {
    return;
  }

  switch (event.key) {
    case "f":
    case "F":
      if (event.shiftKey) {
        event.preventDefault();
        router.push("/search");
      }
      break;
    case "c":
    case "x":
      copyCut(event);
      break;
    case "v":
      paste(event);
      break;
    case "a":
      event.preventDefault();
      for (const item of navigableItems.value) {
        fileStore.addSelected(fileStore.keyFor(item));
      }
      break;
    case "s":
      event.preventDefault();
      document.getElementById("download-button")?.click();
      break;
  }
};

const preventDefault = (event: DragEvent) => {
  if (isExternalFileDrag(event.dataTransfer?.types)) {
    event.preventDefault();
  }
};

const copyCut = (event: Event | KeyboardEvent): void => {
  if (fileStore.scope !== listingScope) return;
  if ((event.target as HTMLElement).tagName?.toLowerCase() === "input") return;

  if (fileStore.req === null) return;

  const items = [];

  for (const item of fileStore.selectedItems) {
    items.push({
      from: item.url,
      name: item.name,
      isDir: item.isDir,
      size: item.size,
      modified: item.modified,
    });
  }

  if (items.length === 0) {
    return;
  }

  clipboardStore.setClipboard({
    key: (event as KeyboardEvent).key,
    items,
    path: route.path,
  });
};

const paste = async (event: Event) => {
  const sourceScope = listingScope;
  if (fileStore.scope !== sourceScope) return;
  if ((event.target as HTMLElement).tagName?.toLowerCase() === "input") return;

  try {
    // TODO router location should it be
    const items: PasteItem[] = [];
    const moving = clipboardStore.key === "x";

    for (const item of clipboardStore.items) {
      const from = item.from.endsWith("/") ? item.from.slice(0, -1) : item.from;
      const to = operationDestinationRoute(route.path, from);
      const sameTarget = operationSameTarget(from, to);
      if (moving && sameTarget) continue;
      items.push({
        from,
        to,
        name: item.name,
        size: item.size ?? 0, // ClipboardItem.size is optional, default to 0
        modified: item.modified,
        isDir: item.isDir,
        overwrite: false,
        rename: !moving && sameTarget,
      });
    }

    if (items.length === 0) {
      return;
    }

    let action = (overwrite?: boolean, rename?: boolean) => {
      if (fileStore.scope !== sourceScope) return;
      api
        .copy(items, overwrite, rename)
        .then((responses) => {
          if (fileStore.scope !== sourceScope) return;
          fileStore.clearSelection();
          fileStore.setPreselect(
            operationAcknowledgedTarget(responses[0], [
              items[0].from,
              items[0].to,
            ]),
            sourceScope
          );
          fileStore.reload = true;
        })
        .catch((error) => {
          if (fileStore.scope === sourceScope) $showError(error);
        });
    };

    if (moving) {
      action = (overwrite, rename) => {
        if (fileStore.scope !== sourceScope) return;
        api
          .move(items, overwrite, rename)
          .then((responses) => {
            if (fileStore.scope !== sourceScope) return;
            clipboardStore.resetClipboard();
            fileStore.clearSelection();
            fileStore.setPreselect(
              operationAcknowledgedTarget(responses[0], [
                items[0].from,
                items[0].to,
              ]),
              sourceScope
            );
            fileStore.reload = true;
          })
          .catch((error) => {
            if (fileStore.scope === sourceScope) $showError(error);
          });
      };
    }

    const path = route.path.endsWith("/") ? route.path : route.path + "/";
    const conflict = (
      await upload.checkConflict(items as PasteItem[], path)
    ).filter(({ index }) => !items[index].rename);
    if (fileStore.scope !== sourceScope) return;

    if (conflict.length > 0) {
      layoutStore.showHover({
        prompt: "resolve-conflict",
        props: {
          conflict: conflict,
        },
        confirm: (event: Event, result: Array<ConflictingResource>) => {
          event.preventDefault();
          if (fileStore.scope !== sourceScope) return;
          layoutStore.closeHovers();
          for (let i = result.length - 1; i >= 0; i--) {
            const item = result[i];
            if (item.checked.length == 2) {
              items[item.index].rename = true;
            } else if (
              item.checked.length == 1 &&
              item.checked[0] == "origin"
            ) {
              items[item.index].overwrite = true;
            } else {
              items.splice(item.index, 1);
            }
          }
          if (items.length > 0) {
            action();
          }
        },
      });

      return;
    }

    action(false, false);
  } catch (error) {
    if (fileStore.scope === sourceScope) $showError(error as Error);
  }
};

const scrollEvent = throttle(() => {
  const totalItems = navigableItems.value.length;

  // All items are displayed
  if (showLimit.value >= totalItems) return;

  const currentPos = window.innerHeight + window.scrollY;

  // Trigger at the 75% of the window height
  const triggerPos = document.body.offsetHeight - window.innerHeight * 0.25;

  if (currentPos > triggerPos) {
    // Quantity of items needed to fill 2x of the window height
    const showQuantity = Math.ceil((window.innerHeight * 2) / itemWeight.value);

    // Increase the number of displayed items
    showLimit.value += showQuantity;
  }
}, 100);

const dragEnter = (event: DragEvent) => {
  if (!isExternalFileDrag(event.dataTransfer?.types)) return;
  dragCounter.value++;

  // When the user starts dragging an item, put every
  // file on the listing with 50% opacity.
  const items = document.getElementsByClassName("item");

  Array.from(items).forEach((file: Element) => {
    (file as HTMLElement).style.opacity = "0.5";
  });
};

const dragLeave = (event: DragEvent) => {
  if (!isExternalFileDrag(event.dataTransfer?.types)) return;
  dragCounter.value--;

  if (dragCounter.value == 0) {
    resetOpacity();
  }
};

const drop = async (event: DragEvent) => {
  const sourceScope = listingScope;
  if (fileStore.scope !== sourceScope) return;
  const dt = event.dataTransfer;
  if (!isExternalFileDrag(dt?.types)) return;
  event.preventDefault();
  dragCounter.value = 0;
  resetOpacity();

  let el: HTMLElement | null = event.target as HTMLElement;

  if (fileStore.req === null || dt === null || dt.files.length <= 0) return;

  for (let i = 0; i < 5; i++) {
    if (el !== null && !el.classList.contains("item")) {
      el = el.parentElement;
    }
  }

  const files: UploadList = (await upload.scanFiles(dt)) as UploadList;
  if (fileStore.scope !== sourceScope) return;
  let path = route.path.endsWith("/") ? route.path : route.path + "/";

  if (
    el !== null &&
    el.classList.contains("item") &&
    el.dataset.dir === "true"
  ) {
    // Get url from data attribute
    path = el.dataset.url || path;

    try {
      (await api.fetch(path)).items;
    } catch (error: any) {
      $showError(error);
      return;
    }
  }

  const conflict = await upload.checkConflict(files, path);

  if (fileStore.scope !== sourceScope) return;
  const preselect = removePrefix(path) + (files[0].fullPath || files[0].name);

  if (conflict.length > 0) {
    layoutStore.showHover({
      prompt: "resolve-conflict",
      props: {
        conflict: conflict,
        isUploadAction: true,
      },
      confirm: (event: Event, result: Array<ConflictingResource>) => {
        event.preventDefault();
        if (fileStore.scope !== sourceScope) return;
        layoutStore.closeHovers();
        for (let i = result.length - 1; i >= 0; i--) {
          const item = result[i];
          if (item.checked.length == 2) {
            continue;
          } else if (item.checked.length == 1 && item.checked[0] == "origin") {
            files[item.index].overwrite = true;
          } else {
            files.splice(item.index, 1);
          }
        }
        if (files.length > 0) {
          upload.handleFiles(files, path, true);
          fileStore.setPreselect(preselect, sourceScope);
        }
      },
    });

    return;
  }

  upload.handleFiles(files, path);
  fileStore.setPreselect(preselect, sourceScope);
};

const uploadInput = (event: Event) => {
  upload.processFileInput(event, route.path, layoutStore);
};

const resetOpacity = () => {
  const items = document.getElementsByClassName("item");

  Array.from(items).forEach((file: Element) => {
    (file as HTMLElement).style.opacity = "1";
  });
};

const searchBasePath = computed(() => {
  return fileStore.req?.path
    ? normalizeSearchBase(fileStore.req.path)
    : normalizeFilesRouteBase(route.path || "/files/");
});

const submitInlineSearch = () => {
  const query = inlineSearch.value.trim();
  if (!query) return;
  router.push({
    path: "/search",
    query: {
      q: query,
      base: searchBasePath.value,
      scope: "current",
    },
  });
};

const clearInlineSearch = () => {
  inlineSearch.value = "";
};

const toggleMultipleSelection = () => {
  fileStore.toggleMultiple();
  layoutStore.closeHovers();
};

const clearSelection = () => {
  fileStore.clearSelection();
};

const analyzeSelection = () => {
  const paths = fileStore.selectedItems.map((item) => item.path);
  if (paths.length === 0) return;
  hideContextMenu();
  void router.push({ path: "/analysis", query: { paths } });
};

const showContextPrompt = (prompt: string) => {
  hideContextMenu();
  layoutStore.showHover(prompt);
};

const runContextDownload = () => {
  hideContextMenu();
  download();
};

const runContextAnalysis = () => {
  hideContextMenu();
  analyzeSelection();
};

const selectAll = () => {
  fileStore.setSelected(
    navigableItems.value.map((item) => fileStore.keyFor(item))
  );
};

const invertSelection = () => {
  const allKeys = new Set<string>(
    navigableItems.value.map((item) => fileStore.keyFor(item))
  );
  const selectedSet = new Set(fileStore.selected);
  fileStore.setSelected([...allKeys].filter((key) => !selectedSet.has(key)));
};

const resizeListing = throttle((entries: ResizeObserverEntry[]) => {
  const entry = entries.at(-1);
  if (!entry || listing.value == null) return;

  // How much every listing item affects the window height
  setItemWeight();

  // Fill but not fit the window
  fillWindow();
}, 100);

const transcode = () => {
  if (!fileStore.req) return;
  layoutStore.showHover({
    prompt: "transcode",
    props: {
      paths: fileStore.selectedCount
        ? fileStore.selectedItems.map((item) => item.url)
        : [route.path],
      initialDestination: route.path,
    },
  });
};

const download = () => {
  if (fileStore.req === null) return;

  if (fileStore.selectedCount === 1 && !fileStore.selectedItems[0].isDir) {
    api.download(null, fileStore.selectedItems[0].url);
    return;
  }

  layoutStore.showHover({
    prompt: "download",
    confirm: (format: DownloadFormat) => {
      layoutStore.closeHovers();

      const files = [];

      if (fileStore.selectedCount > 0 && fileStore.req !== null) {
        for (const item of fileStore.selectedItems) {
          files.push(item.url);
        }
      } else {
        files.push(route.path);
      }

      api.download(format, ...files);
    },
  });
};

const toggleViewDropdown = () => {
  showSortDropdown.value = false;
  showViewDropdown.value = !showViewDropdown.value;
};

const toggleSortDropdown = () => {
  showViewDropdown.value = false;
  showSortDropdown.value = !showSortDropdown.value;
};

const selectViewMode = (mode: ViewModeType) => {
  currentViewMode.value = mode;
  localStorage.setItem("nas-file-browser-view-mode", mode);
  showViewDropdown.value = false;
  if (route.query.view !== mode) {
    void router.push({
      path: route.path,
      query: { ...route.query, view: mode },
      hash: route.hash,
    });
  }

  // Also update server-side preference if logged in
  if (authStore.user?.id) {
    users
      .update({ id: authStore.user.id, viewMode: mode }, ["viewMode"])
      .catch(() => {});
    authStore.updateUser({ viewMode: mode });
  }

  setItemWeight();
  fillWindow();
};

const selectCompactGridSize = (size: CompactGridSize) => {
  compactGridSize.value = size;
  localStorage.setItem("nas-file-browser-compact-grid-size", size);
  showViewDropdown.value = false;
};

const selectWindowsIconSize = (size: CompactGridSize) => {
  windowsIconSize.value = size;
  localStorage.setItem(windowsIconSizeKey, size);
  showViewDropdown.value = false;
  setItemWeight();
  fillWindow();
};

const cycleSort = (by: string) => {
  const next = cycleListingSort(
    {
      by: currentSortBy.value,
      asc: currentSortAsc.value,
      overridden: sortIsOverridden.value,
    },
    by,
    { by: accountSortBy.value, asc: accountSortAsc.value }
  );
  currentSortBy.value = next.by;
  currentSortAsc.value = next.asc;
  sortIsOverridden.value = next.overridden;
  const sorting = { by: next.by, asc: next.asc };
  if (!authStore.user?.id) {
    try {
      writeGuestSorting(sorting);
      accountSortBy.value = sorting.by;
      accountSortAsc.value = sorting.asc;
    } catch {
      $showError(new Error("无法保存排序偏好"));
    }
    return;
  }
  void accountPreferencesStore
    .save({ sorting })
    .then(() => {
      accountSortBy.value = sorting.by;
      accountSortAsc.value = sorting.asc;
    })
    .catch((error) =>
      $showError(error instanceof Error ? error : new Error("排序偏好保存失败"))
    );
};

const sortByHeader = (by: string) => {
  cycleSort(by);
};

const headerSortState = (by: string) => {
  if (!sortIsOverridden.value || currentSortBy.value !== by) return "none";
  return currentSortAsc.value ? "ascending" : "descending";
};

const resetSortOverride = () => {
  sortIsOverridden.value = false;
  currentSortBy.value = accountSortBy.value;
  currentSortAsc.value = accountSortAsc.value;
  showSortDropdown.value = false;
};

// Close dropdowns on outside click
const handleOutsideClick = (e: MouseEvent) => {
  const target = e.target as HTMLElement;
  if (
    showViewDropdown.value &&
    viewDropdownRef.value &&
    !viewDropdownRef.value.contains(target)
  ) {
    showViewDropdown.value = false;
  }
  if (
    showSortDropdown.value &&
    sortDropdownRef.value &&
    !sortDropdownRef.value.contains(target)
  ) {
    showSortDropdown.value = false;
  }
};

const uploadFunc = () => {
  if (
    typeof window.DataTransferItem !== "undefined" &&
    typeof DataTransferItem.prototype.webkitGetAsEntry !== "undefined"
  ) {
    layoutStore.showHover("upload");
  } else {
    document.getElementById("upload-input")?.click();
  }
};

const setItemWeight = () => {
  // Listing element is not displayed
  if (listing.value === null || fileStore.req === null) return;

  let itemQuantity = navigableItems.value.length;
  if (itemQuantity > showLimit.value) itemQuantity = showLimit.value;
  if (itemQuantity === 0) {
    itemWeight.value = 60;
    return;
  }

  // How much every listing item affects the window height
  itemWeight.value = listing.value.offsetHeight / itemQuantity;
};

const fillWindow = (fit = false) => {
  if (fileStore.req === null) return;

  const totalItems = navigableItems.value.length;

  // More items are displayed than the total
  if (showLimit.value >= totalItems && !fit) return;

  const windowHeight = window.innerHeight;

  // Quantity of items needed to fill 2x of the window height
  const showQuantity = Math.ceil(
    (windowHeight + windowHeight * 2) / itemWeight.value
  );

  // Less items to display than current
  if (showLimit.value > showQuantity && !fit) return;

  // Set the number of displayed items
  showLimit.value = showQuantity > totalItems ? totalItems : showQuantity;
};

const revealPreviousItem = () => {
  if (!fileStore.req || !fileStore.oldReq) return;

  const selectedKey = fileStore.selected[0];
  if (selectedKey === undefined) return;
  const index = visibleItemKeys.value.indexOf(selectedKey);
  if (index < 0) return;

  showLimit.value =
    index + Math.ceil((window.innerHeight * 2) / itemWeight.value);

  nextTick(() => {
    scrollItemIntoView(selectedKey, "center");
  });

  return true;
};

const showContextMenu = (event: MouseEvent) => {
  event.preventDefault();
  isContextMenuVisible.value = false;
  if (fileStore.scope !== listingScope) return;

  const target = event.target;
  if (target instanceof HTMLElement) {
    const item = target.closest<HTMLElement>(".item");
    const targetKey = item?.dataset.key;
    if (item && (!targetKey || !fileStore.itemForKey(targetKey))) return;
    if (targetKey) {
      fileStore.setSelected(
        selectForContextMenu(fileStore.selected, targetKey),
        targetKey
      );
    }
  }

  isContextMenuVisible.value = true;
  contextMenuPos.value = {
    x: event.clientX + 8,
    y: event.clientY + 8,
  };
};

const hideContextMenu = () => {
  isContextMenuVisible.value = false;
};

const handleEmptyAreaClick = (e: MouseEvent) => {
  const target = e.target;
  if (!(target instanceof HTMLElement)) return;

  if (target.dataset.clearOnClick === "true") {
    fileStore.clearSelection();
  }
};
</script>
<style scoped>
#listing {
  min-height: calc(100vh - 8rem);
}

.file-selection-margin-bottom {
  margin-bottom: 3.5rem;
}

.loading-skeleton-wrapper {
  min-height: calc(100vh - 8rem);
  padding-top: 0.5em;
}
</style>
