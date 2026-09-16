package com.example.paperassistant.service;

import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.model.dto.LibraryDTO;
import com.example.paperassistant.model.dto.LibraryUpdateDTO;
import java.util.List;

/** 论文库业务接口，不依赖 HTTP 请求或响应类型。 */
public interface LibraryService {

    /**
     * 创建论文库。名称去除首尾空白；当前允许不同论文库重名。
     *
     * @param request 已通过基本校验的创建参数
     * @return 持久化后的论文库
     */
    LibraryDTO createLibrary(LibraryCreateDTO request);

    /**
     * 按创建时间倒序、ID 倒序查询论文库，暂无数据时返回空列表。
     *
     * @return 论文库列表
     */
    List<LibraryDTO> listLibraries();

    /**
     * 替换论文库名称和描述，不存在时抛出 ResourceNotFoundException。
     *
     * @param id 论文库 ID
     * @param request 已通过基本校验的修改参数，描述为 null 时清空
     * @return 更新后的记录
     */
    LibraryDTO updateLibrary(long id, LibraryUpdateDTO request);

    /**
     * 物理删除论文库，不存在时抛出 ResourceNotFoundException。
     * 当前尚无论文关联；接入论文后必须在后端实现关联数据删除规则。
     *
     * @param id 论文库 ID
     */
    void deleteLibrary(long id);
}
