package com.example.paperassistant.service.impl;

import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.model.dataobject.LibraryDO;
import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.model.dto.LibraryDTO;
import com.example.paperassistant.model.dto.LibraryUpdateDTO;
import com.example.paperassistant.service.LibraryService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 论文库业务实现：规范化名称、管理事务、转换持久化对象。 */
@Service
@Profile("postgres")
public class LibraryServiceImpl implements LibraryService {

    private final LibraryDAO libraryDAO;

    public LibraryServiceImpl(LibraryDAO libraryDAO) {
        this.libraryDAO = libraryDAO;
    }

    @Override
    @Transactional
    public LibraryDTO createLibrary(LibraryCreateDTO request) {
        LibraryDO library = new LibraryDO(null, request.name().strip(), request.description(), null, null);
        return toDTO(libraryDAO.insertLibrary(library));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LibraryDTO> listLibraries() {
        return libraryDAO.listLibraries().stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public LibraryDTO updateLibrary(long id, LibraryUpdateDTO request) {
        LibraryDO changes = new LibraryDO(id, request.name().strip(), request.description(), null, null);
        return libraryDAO.updateLibrary(changes)
                .map(this::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("论文库不存在：" + id));
    }

    @Override
    @Transactional
    public void deleteLibrary(long id) {
        if (libraryDAO.deleteLibrary(id) == 0) {
            throw new ResourceNotFoundException("论文库不存在：" + id);
        }
    }

    private LibraryDTO toDTO(LibraryDO library) {
        return new LibraryDTO(library.id(), library.name(), library.description(),
                library.gmtCreate(), library.gmtModified());
    }
}
